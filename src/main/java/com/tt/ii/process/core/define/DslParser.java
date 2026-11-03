package com.tt.ii.process.core.define;

import com.tt.ii.process.exception.ProcessException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * flow DSL 解析(扁平化): "A,B,PARALLEL(C,D),IF(x,E,F)" -> 顶层表达式列表。
 *   - 顶层逗号 = 平级节点(不包 SERIAL 虚拟根, 顶层即多个根节点)。
 *   - Parallel := PARALLEL[:name] (items...)    If := IF(cond, then[, else])    Leaf := nodeRef
 *   - **命名后缀** {@code PARALLEL:checks(a,b)}: name = 这条并行的**声明键**(指向 nodes 下的纯容器条目),
 *     于是并行容器也能像聚合节点那样就近声明 errorPolicy / when / config / 节点级监听器
 *     (不写后缀 = 匿名并行, 一律吃流程级全局策略)。命名写在**关键字与括号之间**, 不占用子项位置,
 *     括号里永远只有形状; 将来 IF 要获得同一能力也是补一个后缀, 语法位不用再吵一次。
 *     冒号因此仍是保留字符: 节点引用里出现它一律拒(AU204107), 旧写法 {@code PARALLEL(checks: a,b)}
 *     因此在括号内冒号处当场报错而不是被当成一个叫 checks 的节点引用(不留静默退化)。
 *   - Group := GROUP:name(items...)  **命名串行容器**(聚合节点): name = nodes 下声明的聚合节点键,
 *     item 为其子表达式。它由 DefinitionLoader 在装载期从 {@code nodes.<key>.group} 段展开并**重写回 flow 文本**
 *     (定义快照只存 flow 字符串, 展开结果必须落在可序列化字段里, 见 DefinitionLoader.expandGroups);
 *     名字**必须写**(旧写法 {@code GROUP(名字,a,b)} 与新写法 {@code GROUP(a,b)} 文本一模一样却形状不同:
 *     前者 1 个名叫名字的容器、后者匿名容器带两个子项, 光看文本无法区分。静默按后者解读会多物化一行组名,
 *     所以无名 GROUP 一律 AU204102 拒掉, 要匿名串行容器就写带名而名字未声明——那才是既有的退化口径)。
 *     名字写了但 nodes 下无对应声明 = 退化成"匿名串行容器"(errorPolicy 吃全局、无组级 guard), 与旧口径一致。
 *   - **空白不敏感**: token 之间的空格/制表/换行(含 Windows CRLF)一律跳过, 多行块写法与单行等价;
 *     尾随逗号(容器子项列表与整条 flow 的结尾)一律当收口处理({@code PARALLEL(a,b,)} /
 *     {@code IF(c,a,b,)} / {@code "a,b,"} 均可), 不产出空表达式(FR-DSL-08); 真正的空项
 *     ({@code a,,b}) 仍按缺节点引用报 AU204105。
 *
 * 表达式的**实例化定位**不做路径反查: 驱动一律按定义顺序前向走查, 实例行按
 * auto_id(声明顺序)与表达式列表按下标对齐(第 i 行 ↔ 第 i 个表达式);
 * 已执行过的行跳过, 缺行则用手里正在走的表达式补物化(见 Materializer.rootAt/childAt/ensureRoots)。
 */
public final class DslParser {

    /** flow DSL 图元关键字: 解析侧(matchWord)与写回侧(appendFlow)必须一字不差, 否则 parseFlow ↔ toFlow 不再互逆。 */
    private static final String KW_PARALLEL = "PARALLEL";
    private static final String KW_IF = "IF";
    private static final String KW_GROUP = "GROUP";

    /**
     * 表达式的**类型自述**(类型知识归类型自己, 不再由外部按 instanceof 分派)。四件事都在这里, 一处不漏:
     *  - {@link #children()} 声明顺序的直接子表达式: 装载期校验/重写与驱动期走查都按它递归;
     *  - {@link #defRef()} 指向 nodes 下声明的引用键: 对齐时据此挂 NodeDef(匿名容器无声明 = null);
     *  - {@link #containerLabel()} 需要与 nodes 声明**配对校验**的容器命名后缀(只有命名并行是用户手敲的);
     *  - {@link #appendFlow(StringBuilder)} 写回 DSL 文本(与 {@link #parseFlow} 互逆, 定义快照靠它)。
     *
     * 新增一种表达式 = 新增一个 Expr 实现并覆写这几条; 遍历/校验/渲染/取子项各处都不用再改
     * (以前要动 5 条 instanceof 链, 漏一条就是"命名容器不认自己的声明"这种拖到运行期的坑)。
     */
    public interface Expr {

        /** 声明顺序的直接子表达式(叶子恒为空列表, 不返回 null)。 */
        List<Expr> children();

        /** nodes 下的声明引用键(叶子 = nodeRef, 命名容器 = name); 匿名 PARALLEL/IF 无声明 = null。 */
        String defRef();

        /**
         * 装载期必须与 nodes 声明成对的容器命名后缀: 只有**命名并行**是用户手敲的(拼错就静默回落全局策略, 所以当场拒,
         * 见 DefinitionLoader.validateNamedParallels); GROUP 的名字来自 group 段展开或允许退化匿名, 叶子不是容器——都回 null。
         */
        default String containerLabel() {
            return null;
        }

        /** 写回 flow DSL 片段(与 {@link #parseFlow} 互逆)。 */
        void appendFlow(StringBuilder sb);
    }

    /**
     * 装载期展开聚合节点时需要**按新子项原样重建**的容器表达式(并行/条件):
     * 只换子项, 关键字、命名后缀与形状都必须一字不变(漏一项就是静默丢命名)。
     * GROUP 不实现它——它本身就是 group 段的展开产物, 组内子项既不重绑定也不替换(见 DefinitionLoader)。
     */
    public interface ContainerExpr extends Expr {

        /** 用新的子表达式重建本容器(子项个数与顺序由调用方保证与 {@link #children()} 对应)。 */
        Expr withChildren(List<Expr> children);
    }

    /**
     * 并行容器: 子表达式全部扇出并发, join 按 severity 聚合。
     * {@code name} 可空——空 = 匿名并行(无 NodeDef 可挂, 策略/guard 一律回落流程级全局);
     * 非空 = flow 里写在关键字后的命名后缀 {@code PARALLEL:名字(a,b)}, 指向 nodes 下的纯容器条目, 与 {@link Group} 同一个套路。
     */
    public static final class Parallel implements ContainerExpr {
        public String name;                          // 命名后缀给出的声明键; null = 匿名
        public final List<Expr> items = new ArrayList<>();

        @Override public List<Expr> children() {
            return items;
        }

        @Override public String defRef() {
            return name;
        }

        @Override public String containerLabel() {
            return name;                                   // 命名后缀是用户手敲的, 装载期要跟声明配对校验
        }

        @Override public Expr withChildren(List<Expr> children) {
            Parallel out = new Parallel();
            out.name = name;
            out.items.addAll(children);
            return out;
        }

        @Override public void appendFlow(StringBuilder sb) {
            sb.append(KW_PARALLEL);
            if (name != null) {
                sb.append(':').append(name);        // 命名后缀写回原样(括号之前), 保证 parseFlow ↔ toFlow 互逆
            }
            sb.append('(');
            appendItems(sb, items);
            sb.append(')');
        }
    }

    /**
     * 条件容器: 两个分支都是**候选**子项(定义侧遍历两支都算), 运行期只驱动选中的那支。
     * 无声明键(defRef = null): IF 写在 flow 里, 不占 nodes 下的一条声明, 条件文本落在 condition 上。
     */
    public static final class If implements ContainerExpr {
        public String condition;
        public Expr thenBranch;
        public Expr elseBranch;   // 可空

        @Override public List<Expr> children() {
            List<Expr> out = new ArrayList<>();
            if (thenBranch != null) {
                out.add(thenBranch);
            }
            if (elseBranch != null) {
                out.add(elseBranch);
            }
            return out;
        }

        @Override public String defRef() {
            return null;                                   // 匿名: 不吃 NodeDef
        }

        @Override public Expr withChildren(List<Expr> children) {
            If out = new If();
            out.condition = condition;
            out.thenBranch = children.isEmpty() ? null : children.get(0);
            out.elseBranch = children.size() > 1 ? children.get(1) : null;
            return out;
        }

        @Override public void appendFlow(StringBuilder sb) {
            // condition 由 readUntilCommaOrClose 取出, 本身不含顶层逗号, 故直接拼回不会破坏语法
            sb.append(KW_IF).append('(').append(condition).append(',');
            append(sb, thenBranch);
            if (elseBranch != null) {
                sb.append(',');
                append(sb, elseBranch);
            }
            sb.append(')');
        }
    }

    public static final class Leaf implements Expr {
        public final String nodeRef;

        public Leaf(String nodeRef) {
            this.nodeRef = nodeRef;
        }

        @Override public List<Expr> children() {
            return Collections.emptyList();                // 叶子没有子表达式(它下面的 lane/action 不是定义树的一层)
        }

        @Override public String defRef() {
            return nodeRef;
        }

        @Override public void appendFlow(StringBuilder sb) {
            sb.append(nodeRef);
        }
    }

    /** 聚合节点: 有名字的串行容器, 子表达式按声明顺序逐个驱动(组级共享靠显式放置, 见 GroupKind)。 */
    public static final class Group implements Expr {
        public final String name;                        // nodes 下的声明键(= NodeDef.id); 恒非空(无名在解析期就 AU204102)
        public final List<Expr> items = new ArrayList<>();

        public Group(String name) {
            this.name = name;
        }

        @Override public List<Expr> children() {
            return items;
        }

        @Override public String defRef() {
            return name;
        }

        @Override public void appendFlow(StringBuilder sb) {
            sb.append(KW_GROUP).append(':').append(name).append('(');
            appendItems(sb, items);
            sb.append(')');
        }
    }

    private final String src;
    private int pos;

    private DslParser(String src) {
        this.src = src;
    }

    /** 解析 flow, 返回顶层表达式列表(空 flow 返回空列表)。 */
    public static List<Expr> parseFlow(String flow) {
        DslParser p = new DslParser(flow == null ? "" : flow.trim());
        List<Expr> roots = p.parseItems();
        p.skipWs();
        if (p.pos < p.src.length()) {
            throw new ProcessException("AU204101", p.src.substring(p.pos));
        }
        return roots;
    }

    // ===================== 解析 =====================

    /** 解析逗号分隔的表达式列表(顶层 / PARALLEL / GROUP 内共用)。 */
    private List<Expr> parseItems() {
        List<Expr> items = new ArrayList<>();
        skipWs();
        if (pos >= src.length()) {
            return items;
        }
        items.add(parseExpr());
        while (true) {
            skipWs();
            if (pos < src.length() && src.charAt(pos) == ',') {
                if (consumeTrailingComma()) {
                    break;                       // 尾随逗号: 逗号与空白已吃掉(下一位置是 ')' 或输入结束)
                }
                pos++;
                items.add(parseExpr());
            } else {
                break;
            }
        }
        return items;
    }

    /**
     * 逗号后(跳过空白/换行)只剩 ')' 或输入结束 = 尾随逗号(FR-DSL-08): 吃掉逗号与空白, 不产出空表达式。
     * 多行块写法每行(包括最后一行)都可以带逗号; 只有逗号后面还有实项时才继续解析
     * (真正的空项 {@code a,,b} 仍由 readToken 报 AU204105)。
     */
    private boolean consumeTrailingComma() {
        int j = pos + 1;
        while (j < src.length() && Character.isWhitespace(src.charAt(j))) {
            j++;
        }
        if (j >= src.length() || src.charAt(j) == ')') {
            pos = j;
            return true;
        }
        return false;
    }

    /**
     * 读关键字与左括号之间的**命名后缀** {@code :名字}(PARALLEL 可省 = 匿名并行; GROUP 必填, 见类注释的歧义缘由)。
     *
     * @param required 名字缺失时是否当场拒(GROUP 恒 true: 旧写法 {@code GROUP(名字,a,b)} 缺后缀会被读成"匿名容器带个名叫名字的子项",
     *                 子项数差一个 = 实例行与表达式错位, 所以宁可拒也不猜)
     */
    private String readContainerName(String keyword, boolean required) {
        skipWs();
        if (pos >= src.length() || src.charAt(pos) != ':') {
            if (required) {
                throw new ProcessException("AU204102", keyword, pos);
            }
            return null;
        }
        pos++;                                        // 消费冒号
        skipWs();
        if (pos >= src.length() || src.charAt(pos) == '(') {
            throw new ProcessException("AU204109", keyword, pos);   // "PARALLEL:(a,b)" 冒号后光秃秃
        }
        return readToken();                           // 名字里再含冒号/括号/空白 -> AU204107/AU204105
    }

    /** 当前位置(跳过空白后)是不是右括号——用来区分"括号里没子项"和"括号里有子项"。 */
    private boolean peekIsClose() {
        skipWs();
        return pos < src.length() && src.charAt(pos) == ')';
    }

    private Expr parseExpr() {
        skipWs();
        if (matchWord(KW_PARALLEL)) {
            Parallel p = new Parallel();
            p.name = readContainerName(KW_PARALLEL, false);   // 可空: 命名后缀 = 括号前的 ":名字"(指向 nodes 下的纯容器声明)
            expect('(');
            if (p.name != null && peekIsClose()) {
                throw new ProcessException("AU204108", p.name);   // PARALLEL:checks() 光有名字没子项
            }
            p.items.addAll(parseItems());
            expect(')');
            if (p.name != null && p.items.isEmpty()) {
                throw new ProcessException("AU204108", p.name);   // 同上, 兜住 "PARALLEL:checks( )" 这类带空白的写法
            }
            return p;
        }
        if (matchWord(KW_IF)) {
            skipWs();
            if (pos < src.length() && src.charAt(pos) == ':') {
                // IF 还没有"纯容器声明"这套口径(条件本身占着括号里的首位), 先把语法位占住报清楚,
                // 免得写成 IF:名(...) 的人以为生效了——它只会在下一个 expect('(') 上报一句看不出根由的失配
                throw new ProcessException("AU204110", KW_IF);
            }
            expect('(');
            If iff = new If();
            iff.condition = readUntilCommaOrClose();   // 条件可能含空格/换行/比较符
            skipWs();
            if (pos < src.length() && src.charAt(pos) == ',') {
                pos++;
            }
            iff.thenBranch = parseExpr();
            skipWs();
            if (pos < src.length() && src.charAt(pos) == ',') {
                if (!consumeTrailingComma()) {         // IF(c,then,) 的尾随逗号 = 只有 then 支
                    pos++;
                    iff.elseBranch = parseExpr();
                    skipWs();
                    if (pos < src.length() && src.charAt(pos) == ',') {
                        consumeTrailingComma();        // else 后的尾随逗号; 非尾随则不吃, 交给 expect(')') 报错
                    }
                }
            }
            expect(')');
            return iff;
        }
        if (matchWord(KW_GROUP)) {
            String name = readContainerName(KW_GROUP, true);    // 聚合节点在 nodes 下的声明键, 写在括号之前
            expect('(');
            if (peekIsClose()) {
                throw new ProcessException("AU204103", name);
            }
            Group g = new Group(name);
            g.items.addAll(parseItems());
            expect(')');
            if (g.items.isEmpty()) {
                throw new ProcessException("AU204103", name);   // 同上, 兜住带空白的括号空写法
            }
            return g;
        }
        return new Leaf(readToken());
    }

    // ===================== 写回(装载期展开聚合节点后重写 flow 文本) =====================

    /**
     * 把表达式树写回 flow DSL 文本(与 {@link #parseFlow} 互逆)。
     * 用途: DefinitionLoader 展开 {@code nodes.<key>.group} 后, 必须把结果**写回 flow 字符串**——
     * 定义缓存存的是 JSON 快照而 roots 是 transient, 借出的副本各自重解析 flow, 展开结果只放内存就会丢。
     */
    public static String toFlow(List<Expr> exprs) {
        StringBuilder sb = new StringBuilder();
        appendItems(sb, exprs);
        return sb.toString();
    }

    private static void appendItems(StringBuilder sb, List<Expr> items) {
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            append(sb, items.get(i));
        }
    }

    /** 写回单个表达式: 渲染知识在 Expr 自己身上(见 {@link Expr#appendFlow}), 这里只兜住 null。 */
    private static void append(StringBuilder sb, Expr e) {
        if (e == null) {
            throw new ProcessException("AU204104", "null");
        }
        e.appendFlow(sb);
    }

    private String readUntilCommaOrClose() {
        int start = pos;
        int depth = 0;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) {
                    break;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                break;
            }
            pos++;
        }
        return src.substring(start, pos).trim();
    }

    private String readToken() {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ',' || c == ')' || c == '(' || Character.isWhitespace(c)) {
                break;
            }
            pos++;
        }
        if (start == pos) {
            throw new ProcessException("AU204105", pos);
        }
        String tok = src.substring(start, pos).trim();
        if (tok.indexOf(':') >= 0) {
            // 冒号只允许出现在关键字与括号之间(那份命名早在 readContainerName 消费掉): 走到这里说明
            // 它落在普通节点引用里, 或是旧写法 PARALLEL(标签: a,b) 的括号内冒号——都当场拒, 不给静默退化的机会
            throw new ProcessException("AU204107", tok);
        }
        return tok;
    }

    private boolean matchWord(String w) {
        skipWs();
        if (src.startsWith(w, pos) && (pos + w.length() >= src.length()
                || !Character.isLetterOrDigit(src.charAt(pos + w.length())))) {
            pos += w.length();
            skipWs();
            return true;
        }
        return false;
    }

    private void expect(char c) {
        skipWs();
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw new ProcessException("AU204106", c, pos);
        }
        pos++;
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }
}