package cn.embedded.quiz;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

// 【3／4：本地数据库】统一管理 embedded.db。MainActivity 通过它读写学习数据。
// questions＝题库，mistakes＝错题，bookmarks＝收藏，attempts＝已交卷记录。
// SQLiteOpenHelper 在首次打开数据库时创建表，在版本升高时调用升级方法。
public final class Store extends SQLiteOpenHelper {
    // 保留 Android 上下文，用来读取应用安装包 assets 中的初始题库。
    private final Context context;

    /**
     * 创建数据库辅助对象。embedded.db 是数据库文件名，1 是当前数据库结构版本。
     * new Store 本身不等于马上建表，真正打开数据库时才触发框架相应回调。
     */
    public Store(Context context) {
        super(context,"embedded.db",null,1);
        this.context=context;
    }

    /**
     * 框架首次创建数据库时调用：先建表，再导入内置题库。
     * 已有数据库正常重开不会再次初始化，因此只修改 assets 并覆盖安装，不会自动更新已有题库。
     */
    @Override public void onCreate(SQLiteDatabase db) {
        // 主键 id 防止题目重复；body 保存题干、可变数量选项、标准答案、解析等 JSON 数据。
        db.execSQL("CREATE TABLE questions(id TEXT PRIMARY KEY, category TEXT NOT NULL, body TEXT NOT NULL)");
        // misses 是累计错误次数；mastered=0 表示待巩固，1 表示已掌握。这里的 ID 关联没有声明数据库外键。
        db.execSQL("CREATE TABLE mistakes(id TEXT PRIMARY KEY, misses INTEGER NOT NULL, mastered INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE bookmarks(id TEXT PRIMARY KEY)");
        // 一次练习一个 UUID；time 为提交毫秒时间，duration 为用时秒数，body 保存整份结果。
        db.execSQL("CREATE TABLE attempts(id TEXT PRIMARY KEY, time INTEGER, mode TEXT, category TEXT, total INTEGER, correct INTEGER, duration INTEGER, body TEXT)");
        // assets 是随 APK 打包的只读初始资源。解析后写入 SQLite，之后收藏和成绩等改变保存在数据库中。
        try(InputStream in=context.getAssets().open("questions.json")) {
            JSONArray data=new JSONObject(read(in)).getJSONArray("questions");
            for(int i=0;i<data.length();i++) insert(db,Question.parse(data.getJSONObject(i)));
        } catch(Exception e) {
            throw new IllegalStateException("初始化题库失败",e);
        }
    }

    /**
     * 数据库结构升级入口。目前版本为 1，此处尚未实现迁移。
     * 以后增加字段应提高版本并补迁移语句，不能直接删库导致学习记录丢失。
     */
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
    }

    /**
     * 把一题写入 questions：独立保存 id、category，完整内容序列化到 body。
     * insertOrThrow 在写入失败时抛出异常，避免调用方把失败当作成功。
     */
    private static void insert(SQLiteDatabase db,Question q) {
        ContentValues v=new ContentValues();
        v.put("id",q.id);
        v.put("category",q.category);
        v.put("body",q.json().toString());
        db.insertOrThrow("questions",null,v);
    }

    /**
     * 以 UTF-8 读取输入流，按 4096 字节分块，超过 2 MB 就拒绝。
     * 去掉 BOM 字符，兼容部分文本编辑器保存的 JSON。流由调用方负责关闭。
     */
    public static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] b=new byte[4096];
        int n;
        while((n=in.read(b))!=-1) {
            out.write(b,0,n);
            if(out.size()>2*1024*1024)throw new IOException("文件不能超过 2 MB");
        }
        return out.toString(StandardCharsets.UTF_8.name()).replace("\uFEFF","");
    }

    /**
     * 查询题目，返回 Java 的题目列表。
     * category=null 表示不限专题；query 为空表示不搜索。
     * filter 可为 mistakes（待巩固）、mastered（已掌握）、bookmarks（收藏），或 null（全部）。
     * 先用 SQL JOIN 筛出相应集合，再在 Java 中做专题和关键词匹配；这不是 SQL 全文检索。
     */
    public List<Question> questions(String category,String query,String filter) {
        List<Question> result=new ArrayList<>();
        String sql="SELECT q.body FROM questions q";
        // JOIN 通过相同题目 ID 关联。这里只拼接固定 SQL 片段，不把用户搜索文本拼进 SQL。
        if("mistakes".equals(filter))sql+=" JOIN mistakes m ON q.id=m.id AND m.mastered=0";
        if("mastered".equals(filter))sql+=" JOIN mistakes m ON q.id=m.id AND m.mastered=1";
        if("bookmarks".equals(filter))sql+=" JOIN bookmarks b ON q.id=b.id";
        sql+=" ORDER BY q.rowid";
        try(Cursor c=getReadableDatabase().rawQuery(sql,null)) {
            while(c.moveToNext()) {
                try {
                    Question q=Question.parse(new JSONObject(c.getString(0)));
                    // 跳过不符合专题或搜索条件的题目；搜索范围是题干、分类和解析，不区分英文字母大小写。
                    if(category!=null && !category.equals(q.category))continue;
                    if(query!=null && !query.isEmpty() && !(q.title+q.category+q.explanation).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))continue;
                    result.add(q);
                } catch(JSONException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return result;
    }

    /**
     * 按专题统计题数。SQL 的 GROUP BY 负责分组，COUNT(*) 负责计数。
     * 按每类最早插入的行排序，再用 LinkedHashMap 保留这一遍历顺序。
     */
    public LinkedHashMap<String,Integer> categories() {
        LinkedHashMap<String,Integer> result=new LinkedHashMap<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT category,COUNT(*) FROM questions GROUP BY category ORDER BY MIN(rowid)",null)) {
            while(c.moveToNext())result.put(c.getString(0),c.getInt(1));
        }
        return result;
    }

    /**
     * 查询收藏表里是否存在该题。? 与参数数组绑定，题目 ID 不直接拼入 SQL。
     */
    public boolean bookmarked(String id) {
        return scalar("SELECT COUNT(*) FROM bookmarks WHERE id=?",new String[]{
            id
        })>0;
    }

    /**
     * 切换收藏：已收藏就删除记录，未收藏就插入题目 ID。
     * 收藏表只存 ID，不复制整道题。
     */
    public void toggleBookmark(String id) {
        if(bookmarked(id))getWritableDatabase().delete("bookmarks","id=?",new String[]{
            id
        });
        else {
            ContentValues v=new ContentValues();
            v.put("id",id);
            getWritableDatabase().insertOrThrow("bookmarks",null,v);
        }
    }

    /**
     * 更新已存在错题的掌握状态：true 写 1，false 写 0。
     * 标记已掌握不会删除错题历史；若该 ID 尚不在错题表，本次更新不会新建记录。
     */
    public void mastery(String id,boolean value) {
        ContentValues v=new ContentValues();
        v.put("mastered",value?1:0);
        getWritableDatabase().update("mistakes",v,"id=?",new String[]{
            id
        });
    }

    /**
     * 查询单个数字，例如 COUNT(*) 或 SUM(...)。返回第一行第一列，没行时返回 0。
     * Cursor 是结果游标，try-with-resources 会自动关闭它。
     */
    public long scalar(String sql,String[] args) {
        try(Cursor c=getReadableDatabase().rawQuery(sql,args)) {
            return c.moveToFirst()?c.getLong(0):0;
        }
    }

    /**
     * 导入一份完整题库，返回实际新增的题数，不包含跳过的已有 ID。
     * 顺序：检查格式版本／题数 → 校验所有题目和文件内 ID → 开事务写入。
     * 文件内重复 ID 报错；数据库已有 ID 跳过，不覆盖已有题目。
     */
    public int importQuestions(String input) throws JSONException {
        JSONObject root=new JSONObject(input);
        if(root.optInt("version",0)!=1)throw new JSONException("仅支持 version 为 1 的题库");
        JSONArray data=root.getJSONArray("questions");
        if(data.length()<1 || data.length()>1000)throw new JSONException("每次请导入 1～1000 道题");
        // pending 暂存已校验题目，ids 用 HashSet 检查本文件中的重复题号。
        // 先把整批验证完，后面某题出错时，前面的题不会已被部分导入。
        List<Question> pending=new ArrayList<>();
        Set<String> ids=new HashSet<>();
        for(int i=0;i<data.length();i++) {
            Question q=Question.parse(data.getJSONObject(i));
            if(!ids.add(q.id))throw new JSONException("文件中 id 重复："+q.id);
            pending.add(q);
        }
        // 事务：所有插入一起成功。只有调用 setTransactionSuccessful，endTransaction 才会提交；否则回滚。
        SQLiteDatabase db=getWritableDatabase();
        int count=0;
        db.beginTransaction();
        try {
            for(Question q:pending) if(scalar("SELECT COUNT(*) FROM questions WHERE id=?",new String[]{
                q.id
            })==0) {
                insert(db,q);
                count++;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return count;
    }

    /**
     * 将草稿提交为已完成的成绩记录，并返回带分数和用时的结果。
     * 顺序：按 UUID 查已提交记录 → 计分 → 在同一事务中整理错题和写入成绩。
     * 正常串行调用时，同一 UUID 重交会直接返回原结果，不重复增加成绩或错误次数。
     * 注意：查重在事务外，这里不应被解释为已完整解决多线程并发提交。
     */
    public JSONObject submit(JSONObject draft) throws JSONException {
        SQLiteDatabase db=getWritableDatabase();
        // 这个 id 标识整次作答，不是题目 ID。已有结果时直接返回，也能处理“成绩保存后，草稿还没来得及清除”的恢复情况。
        String id=draft.getString("id");
        try(Cursor c=db.rawQuery("SELECT body FROM attempts WHERE id=?",new String[]{
            id
        })) {
            if(c.moveToFirst())return new JSONObject(c.getString(0));
        }
        // 从当次草稿快照提取标准答案和用户答案，两个数组相同位置对应同一道题。
        JSONArray qs=draft.getJSONArray("questions"), answers=draft.getJSONArray("answers");
        int[] expected=new int[qs.length()],actual=new int[qs.length()];
        for(int i=0;i<qs.length();i++){
            expected[i]=qs.getJSONObject(i).getInt("answer");
            actual[i]=answers.getInt(i);
        }
        int correct=QuizEngine.correct(expected,actual);
        // 计分时仍使用开始／当前时间计算用时；毫秒除以 1000 转成秒。
        // 限时测验的记录用时不超过分配时长。当前使用可被调整的系统时间，还不是防作弊计时。
        long now=System.currentTimeMillis(),duration=Math.max(0,(now-draft.getLong("started"))/1000);
        if(draft.getLong("deadline")>0)duration=Math.min(duration,(draft.getLong("deadline")-draft.getLong("started"))/1000);
        // 把统计字段补到原草稿对象中，后面保存的 body 因而同时带有题目、用户答案和成绩。
        draft.put("correct",correct).put("score",QuizEngine.score(correct,qs.length())).put("time",now).put("duration",duration);
        // 把错题更新和成绩插入放在同一事务里，避免两种记录只有一部分写成功。
        db.beginTransaction();
        try {
            for(int i=0;i<qs.length();i++) {
                String qid=qs.getJSONObject(i).getString("id");
                // 答对时只有“错题重练”模式会自动标记掌握，普通练习答对不会自动改掌握状态。
                // 未作答的 -1 也不等于正确答案，所以同样计错。
                // 先确保错题记录存在，再让错误次数加一；再次做错会把已掌握状态改回待巩固。
                if(actual[i]!=expected[i]) {
                    db.execSQL("INSERT OR IGNORE INTO mistakes(id,misses,mastered) VALUES(?,0,0)",new Object[]{
                        qid
                    });
                    db.execSQL("UPDATE mistakes SET misses=misses+1,mastered=0 WHERE id=?",new Object[]{
                        qid
                    });
                } else if("错题重练".equals(draft.getString("mode")))mastery(qid,true);
            }
            ContentValues v=new ContentValues();
            v.put("id",id);
            v.put("time",now);
            v.put("mode",draft.getString("mode"));
            v.put("category",draft.getString("category"));
            v.put("total",qs.length());
            v.put("correct",correct);
            v.put("duration",duration);
            v.put("body",draft.toString());
            db.insertOrThrow("attempts",null,v);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return draft;
    }

    /**
     * 读取历史成绩，按提交时间从新到旧排列。body 保留当次题目和答案快照。
     */
    public List<JSONObject> history() {
        List<JSONObject> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT body FROM attempts ORDER BY time DESC",null)) {
            while(c.moveToNext())try {
                result.add(new JSONObject(c.getString(0)));
            }catch(JSONException e){
                throw new IllegalStateException(e);
            }
        }
        return result;
    }

    /**
     * 把全部历史快照放入 JSON 数组，供界面层导出为文件。这里不直接选择或写文件。
     */
    public JSONArray exportHistory() {
        JSONArray a=new JSONArray();
        for(JSONObject h:history())a.put(h);
        return a;
    }
}
