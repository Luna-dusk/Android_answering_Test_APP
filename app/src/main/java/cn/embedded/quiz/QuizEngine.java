package cn.embedded.quiz;

/**
 * 【2／4：计算规则】只接收参数并返回结果，不依赖 Activity、View 或 SQLite。
 * 界面和数据库都可以调用它，普通 Java 检查程序也能单独验证这些规则。
 */
public final class QuizEngine {
    // 这是工具类，构造方法设为 private：调用静态方法即可，不需要 new QuizEngine()。
    private QuizEngine() {
    }

    /**
     * 逐题比较标准答案 expected 与用户答案 actual，返回答对题数。
     * 两个数组必须一一对应且非空。用户未作答时使用 -1；合法标准答案从 0 开始，因此不会相等。
     */
    public static int correct(int[] expected, int[] actual) {
        if (expected.length != actual.length || expected.length == 0) throw new IllegalArgumentException("Invalid answers");
        int count = 0;
        for (int i=0;i<expected.length;i++) if (expected[i] == actual[i]) count++;
        return count;
    }

    /**
     * 把答对题数 right 换算为百分制，total 是本次总题数。
     * 例如 right=2、total=3，返回 67；参数超出有效范围会抛出异常。
     */
    public static int score(int right, int total) {
        if (total<=0 || right<0 || right>total) throw new IllegalArgumentException("Invalid score");
        // 100.0 让除法保留小数，再用 round 四舍五入；若先做整数除法就会丢失精度。
        return (int)Math.round(right * 100.0 / total);
    }

    /**
     * 计算剩余毫秒数：截止时间 deadline 减去当前时间 now。
     * 超时返回 0，避免界面显示负数。两个参数必须使用相同时间基准和单位。
     * 这里只计算时间差，不负责每秒回调，也不负责自动交卷。
     */
    public static long remaining(long deadline, long now) {
        return Math.max(0, deadline-now);
    }
}
