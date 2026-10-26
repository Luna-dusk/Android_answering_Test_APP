package cn.embedded.quiz;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

// 【4／4：界面和交互入口】这个项目在一个 Activity 内切换首页、题库、错题、分析及答题页面。
// 数据分工：Question 描述一道题，QuizEngine 负责计算，Store 负责数据库，本类把它们连接到界面。
// 建议阅读顺序：onCreate → home → startQuiz → showQuestion → saveDraft → submit → analytics。
// “显示一页”通常会重新创建原生 View，并不是启动了另一个 Activity。
public class MainActivity extends Activity {
    // 颜色表，格式为 0xAARRGGBB。BG 是白底，INK 是正文，MUTED 是次要文字，BLUE 是强调色，RED 是错误色。
    private static final int BG=0xFFFFFFFF, INK=0xFF0F172A, MUTED=0xFF64748B, BLUE=0xFF2563EB, BLUE_SOFT=0xFFEFF6FF, LINE=0xFFE2E8F0, RED=0xFFDC2626, ERROR_SOFT=0xFFFEF2F2;
    // 数据库访问入口。页面通过 Store 查询题目、收藏、错题和成绩。
    private Store store;
    // shell 是整页根容器，page 是可滚动的内容容器；底部导航在滚动区域外。
    private LinearLayout shell,page;
    // 未交卷草稿，null 表示没有草稿；有草稿不代表当前一定停留在答题页面。
    private JSONObject draft;
    // tab 保存当前底部栏目；exportText 暂存文件导出内容，等待系统选择好保存位置。
    private String tab="首页", exportText="";
    // 当前题在草稿 questions 数组中的下标，从 0 开始；界面题号显示 index+1。
    private int index=0;
    // 当前是否处在答题流程。返回应用首页会变 false；切到手机后台不会自动变 false。
    private boolean inQuiz=false;
    // 当前答题页的倒计时文字控件，离开或重建页面时可以暂时为 null。
    private TextView timer;
    // 绑定主线程的消息调度器。postDelayed 是延迟安排回调，不会新建工作线程。
    private final Handler handler=new Handler(Looper.getMainLooper());
    // 计时刷新任务：约每秒检查一次，但剩余时间按“截止时刻－当前时刻”计算，不能依赖回调次数。
    // 只有正在答题且 deadline>0 才检查超时；没有实现进程退出后仍后台准点交卷。
    private final Runnable clock=new Runnable(){
        public void run(){
            if(inQuiz && draft!=null && draft.optLong("deadline")>0) {
                long left=QuizEngine.remaining(draft.optLong("deadline"),System.currentTimeMillis());
                // 加 999 再除以 1000，是把剩余毫秒向上取整成显示秒数，避免还没到期就显示 00:00。
                if(timer!=null)timer.setText("剩余 "+duration((left+999)/1000));
                if(left==0){
                    submit();
                    return;
                }
            }
            // 安排下一次刷新。回调受主线程繁忙和系统调度影响，并不保证精确每隔一秒执行。
            handler.postDelayed(this,1000);
        }
    };

    /**
     * Activity 创建入口：打开数据库，读取上次草稿，然后显示首页并安排时钟回调。
     * 恢复草稿后并不直接跳回答题页，用户要从首页点“继续答题”。
     */
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store=new Store(this);
        store.getReadableDatabase();
        try {
            String s=getPreferences(0).getString("draft",null);
            if(s!=null){
                draft=new JSONObject(s);
                index=draft.optInt("index");
            }
        }catch(Exception e){
            getPreferences(0).edit().remove("draft").apply();
        }
        showTab("首页");
        handler.post(clock);
    }

    /**
     * 回到前台时补查超时，避免停留在答题页面期间离开应用错过一次计时回调。
     * 只有 inQuiz 为 true 才在此提交；恢复到首页的草稿会在继续答题后由时钟检查。
     */
    @Override protected void onResume(){
        super.onResume();
        if(inQuiz && draft!=null && draft.optLong("deadline")>0 && System.currentTimeMillis()>=draft.optLong("deadline"))submit();
    }

    /**
     * 离开前台时再保存一次草稿。平时选择答案和切换题目时也会保存，不能只等 onDestroy。
     */
    @Override protected void onPause(){
        saveDraft();
        super.onPause();
    }

    /**
     * 释放 Activity 持有的资源：移除时钟回调，关闭数据库辅助对象。
     * 移除回调可避免旧页面继续收到任务；系统杀进程不保证一定执行此方法。
     */
    @Override protected void onDestroy(){
        handler.removeCallbacks(clock);
        store.close();
        super.onDestroy();
    }

    /**
     * 把布局用的 dp 换算成屏幕像素，适应不同密度的设备。
     */
    private int dp(float n){
        return Math.round(n*getResources().getDisplayMetrics().density);
    }

    /**
     * 创建圆角背景。color 是填充色，radius 是圆角 dp，stroke 是描边颜色；0 表示不画描边。
     */
    private GradientDrawable shape(int color,int radius,int stroke){
        GradientDrawable d=new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if(stroke!=0)d.setStroke(dp(1),stroke);
        return d;
    }

    /**
     * 创建文字控件并统一字号、颜色、粗体、行距。setTextSize 的这个重载默认按 sp 设置。
     */
    private TextView text(String value,int size,int color,boolean bold){
        TextView t=new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if(bold)t.setTypeface(null,Typeface.BOLD);
        t.setLineSpacing(dp(3),1);
        t.setIncludeFontPadding(false);
        return t;
    }

    /**
     * 创建纵向 LinearLayout，后续添加的控件从上到下排列。
     */
    private LinearLayout vertical(){
        LinearLayout l=new LinearLayout(this);
        l.setOrientation(1);
        return l;
    }

    /**
     * 创建横向 LinearLayout，并让一行中的子控件垂直居中。
     */
    private LinearLayout row(){
        LinearLayout l=new LinearLayout(this);
        l.setOrientation(0);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /**
     * 把控件加到纵向父布局：宽度铺满，高度按内容，top 是上方间距 dp。
     * LayoutParams 中 -1 表示 MATCH_PARENT，-2 表示 WRAP_CONTENT。
     */
    private void add(LinearLayout l,View v,int top){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.topMargin=dp(top);
        l.addView(v,p);
    }

    /**
     * 把控件放入横向布局，宽度先设 0，再用权重 1 分配可用空间。
     */
    private void weighted(LinearLayout l,View v){
        l.addView(v,new LinearLayout.LayoutParams(0,-2,1));
    }

    /**
     * 创建统一样式的可点击按钮。primary=true 使用蓝底白字，否则白底蓝字。
     * run 是按钮点击后要执行的动作；v -> run.run() 是点击监听器中的 Lambda 写法。
     */
    private TextView action(String label,boolean primary,Runnable run){
        TextView t=text(label,15,primary?Color.WHITE:BLUE,true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14),dp(15),dp(14),dp(15));
        t.setMinHeight(dp(50));
        t.setBackground(shape(primary?BLUE:Color.WHITE,14,primary?0:LINE));
        t.setOnClickListener(v->run.run());
        t.setFocusable(true);
        return t;
    }

    /**
     * 创建一张白色圆角卡片，设置内边距、边框，再添加到父容器。
     */
    private LinearLayout card(LinearLayout parent,int top){
        LinearLayout c=vertical();
        c.setPadding(dp(18),dp(18),dp(18),dp(18));
        c.setBackground(shape(Color.WHITE,20,LINE));
        add(parent,c,top);
        return c;
    }

    /**
     * 向当前内容页加入大标题，以及可选的小字说明。
     */
    private void heading(String title,String subtitle){
        add(page,text(title,28,INK,true),8);
        if(!subtitle.isEmpty())add(page,text(subtitle,13,MUTED,false),9);
    }

    /**
     * 向当前内容页加入一个分区标题。
     */
    private void section(String title){
        add(page,text(title,18,INK,true),25);
    }

    /**
     * 搭建页面公共骨架：根容器、滚动内容、可选的底部导航。
     * active 是选中的栏目名；navigation 控制是否显示导航。此方法会重新 setContentView。
     */
    private void base(String active,boolean navigation){
        shell=vertical();
        shell.setBackgroundColor(BG);
        setContentView(shell);
        // 读取状态栏、导航栏等系统边距，给根布局留出空间，减少内容与系统栏重叠。
        shell.setOnApplyWindowInsetsListener((v,insets)->{
            shell.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        shell.requestApplyInsets();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        page=vertical();
        page.setPadding(dp(22),dp(18),dp(22),dp(26));
        scroll.addView(page,new ScrollView.LayoutParams(-1,-2));
        shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(navigation){
            LinearLayout nav=row();
            nav.setPadding(dp(5),dp(8),dp(5),dp(9));
            nav.setBackgroundColor(Color.WHITE);
            String[] names={
                "首页","题库","错题","分析"
            };
            String[] icons={
                "⌂","▤","↺","▥"
            };
            for(int i=0;i<names.length;i++){
                final String n=names[i];
                TextView t=text(icons[i]+"\n"+n,13,n.equals(active)?BLUE:MUTED,n.equals(active));
                t.setGravity(Gravity.CENTER);
                t.setPadding(0,dp(6),0,dp(6));
                t.setMinHeight(dp(50));
                t.setContentDescription(n);
                t.setOnClickListener(v->showTab(n));
                weighted(nav,t);
            }
            shell.addView(nav);
        }
    }

    /**
     * 切换四个主要栏目。先退出当前答题显示状态，再创建对应内容。
     * 这里只切换页面，不清空 draft，因此未交卷答案仍能从首页恢复。
     */
    private void showTab(String name){
        inQuiz=false;
        timer=null;
        tab=name;
        base(name,true);
        switch(name){
            case "题库":bank();
            break;
            case "错题":mistakes();
            break;
            case "分析":analytics();
            break;
            default:home();
        }
    }

    /**
     * 取得当前题库中的总题数，包括之后成功导入的题目。
     */
    private long total(){
        return store.scalar("SELECT COUNT(*) FROM questions",null);
    }

    /**
     * 取得尚未掌握的错题数量；已掌握记录仍留在数据库，但不计入此数量。
     */
    private long wrong(){
        return store.scalar("SELECT COUNT(*) FROM mistakes WHERE mastered=0",null);
    }

    /**
     * 首页优先继续未完成的练习；没有草稿时，主按钮才创建随机练习。
     * 题量、作答统计、草稿进度和入口说明均来自实际数据，不使用写死的演示数字。
     */
    private void home(){
        long questionCount=total(), pendingMistakes=wrong();
        int categoryCount=store.categories().size();
        boolean hasDraft=draft!=null;

        // 直接显示应用名称，省去重复的大宣传标题，为练习和复习入口留出首屏空间。
        LinearLayout header=row();
        LinearLayout brand=vertical();
        add(brand,text("嵌入式刷题",24,INK,true),0);
        add(brand,text("练习、复习，记录每一次进步",12,MUTED,false),5);
        weighted(header,brand);
        TextView badge=text("离线学习",10,MUTED,false);
        badge.setPadding(dp(8),dp(5),dp(8),dp(5));
        badge.setBackground(shape(0xFFF1F5F9,16,0));
        header.addView(badge);
        add(page,header,0);

        // 只有主卡片保留柔和的蓝色描边，普通功能卡片使用灰色边框。
        LinearLayout hero=card(page,14);
        hero.setPadding(dp(14),dp(14),dp(14),dp(14));
        hero.setBackground(shape(Color.WHITE,18,0xFFCBDCFF));
        int screenWidthDp=getResources().getConfiguration().screenWidthDp;
        int headlineSize=screenWidthDp<360?18:21;
        int artworkSize=screenWidthDp<360?64:76;
        LinearLayout heroRow=row();
        LinearLayout heroCopy=vertical();
        add(heroCopy,text(hasDraft?"继续上次练习":"今日练习",11,MUTED,true),0);
        add(heroCopy,text("嵌入式知识，\n每天巩固一点。",headlineSize,INK,true),6);
        weighted(heroRow,heroCopy);
        ImageView artwork=new ImageView(this);
        artwork.setImageResource(R.drawable.practice_illustration);
        artwork.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // 插画只作装饰，读屏时不重复朗读；按钮仍是独立的可点击控件。
        artwork.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams artworkParams=new LinearLayout.LayoutParams(dp(artworkSize),dp(artworkSize));
        artworkParams.leftMargin=dp(8);
        heroRow.addView(artwork,artworkParams);
        add(hero,heroRow,0);
        // 已答题数按真正选择了答案的项目计数，不用当前题号代替作答进度。
        add(hero,text(hasDraft?draftProgress():"随机练习 · 即时解析 · 不限时",12,MUTED,false),6);
        TextView start=action(hasDraft?"继续练习   →":"开始练习   →",true,()->{
            if(draft!=null){
                index=draft.optInt("index");
                inQuiz=true;
                showQuestion();
            }else{
                startRandomPractice();
            }
        });
        start.setMinHeight(dp(44));
        start.setPadding(dp(12),dp(11),dp(12),dp(11));
        add(hero,start,12);
        if(hasDraft){
            // 次要入口使用文字样式。开始新练习仍走原来的替换确认，不能直接覆盖草稿。
            LinearLayout draftActions=row();
            weighted(draftActions,homeTextAction("开始新练习",this::startRandomPractice));
            weighted(draftActions,homeTextAction("放弃草稿",this::discardDraft));
            add(hero,draftActions,4);
        }

        // 这里按总正确题次／总题次计算累计正确率，而不是把每份试卷的百分数简单平均。
        long answered=store.scalar("SELECT COALESCE(SUM(total),0) FROM attempts",null), correct=store.scalar("SELECT COALESCE(SUM(correct),0) FROM attempts",null);
        LinearLayout stats=row();
        stats.setPadding(dp(12),dp(10),dp(12),dp(10));
        stats.setBackground(shape(0xFFF8FAFC,14,0));
        homeMetric(stats,String.valueOf(questionCount),"题库总量");
        homeMetric(stats,String.valueOf(answered),"累计题次");
        homeMetric(stats,answered==0?"—":QuizEngine.score((int)correct,(int)answered)+"%","累计正确率");
        add(page,stats,14);

        add(page,text("练习与复习",17,INK,true),18);
        // 两个常用入口并排展示，减少纵向堆叠，让模拟测验和错题巩固更容易在首屏找到。
        LinearLayout shortcuts=row();
        weighted(shortcuts,feature("模拟测验","自选题量 · 限时作答",()->configure(null)));
        LinearLayout.LayoutParams mistakeParams=new LinearLayout.LayoutParams(0,-2,1);
        mistakeParams.leftMargin=dp(10);
        shortcuts.addView(feature("错题巩固",pendingMistakes==0?"暂无错题 · 继续练习":pendingMistakes+" 题待复习",()->showTab("错题")),mistakeParams);
        add(page,shortcuts,10);
        add(page,feature("专题题库",categoryCount+" 个专题 · 共 "+questionCount+" 题",()->showTab("题库")),10);
    }

    /** 返回草稿专题和真实作答数量；答案为 -1 的题目仍算未作答。 */
    private String draftProgress(){
        JSONArray questions=draft.optJSONArray("questions"), answers=draft.optJSONArray("answers");
        int count=questions==null?0:questions.length(), answered=0;
        if(answers!=null)for(int i=0;i<Math.min(count,answers.length());i++)if(answers.optInt(i,-1)>=0)answered++;
        return draft.optString("category","上次练习")+" · 已答 "+answered+"/"+count+" 题";
    }

    /** 新练习统一通过 startQuiz 创建；已有草稿时由它弹出替换确认。 */
    private void startRandomPractice(){
        startQuiz(store.questions(null,"",null),"随机练习","全部分类",10,false);
    }

    /** 只删除未交卷草稿，操作前先确认，已完成成绩保持在数据库中。 */
    private void discardDraft(){
        new AlertDialog.Builder(this).setTitle("放弃这份练习？").setMessage("放弃后将删除未提交的答案，已完成的成绩不受影响。")
            .setNegativeButton("保留",null).setPositiveButton("放弃",(d,w)->{
                draft=null;
                saveDraft();
                showTab("首页");
            }).show();
    }

    /** 首页的次要操作采用灰色文字，避免与唯一的蓝色主按钮竞争。 */
    private TextView homeTextAction(String label,Runnable run){
        TextView t=text(label,12,MUTED,true);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(36));
        t.setPadding(dp(6),dp(6),dp(6),dp(6));
        t.setOnClickListener(v->run.run());
        t.setFocusable(true);
        return t;
    }

    /** 首页统计使用紧凑字号；其他页面仍沿用原来的 metric 样式。 */
    private void homeMetric(LinearLayout parent,String value,String label){
        LinearLayout c=vertical();
        add(c,text(value,20,INK,true),0);
        add(c,text(label,10,MUTED,false),4);
        weighted(parent,c);
    }

    /**
     * 生成“数值＋说明”统计块，放入父布局并使用等权分配。
     */
    private void metric(LinearLayout parent,String value,String label){
        LinearLayout c=vertical();
        add(c,text(value,25,INK,true),0);
        add(c,text(label,11,MUTED,false),6);
        weighted(parent,c);
    }

    /**
     * 返回紧凑的首页入口卡片，由调用方决定并排还是独占一行。
     * 灰色边框和箭头弱化装饰，说明文字展示实际题量或待复习数量。
     */
    private LinearLayout feature(String title,String subtitle,Runnable run){
        LinearLayout c=vertical();
        c.setPadding(dp(14),dp(12),dp(14),dp(12));
        c.setBackground(shape(Color.WHITE,16,LINE));
        LinearLayout r=row();
        weighted(r,text(title,15,INK,true));
        r.addView(text("›",20,MUTED,false));
        add(c,r,0);
        add(c,text(subtitle,11,MUTED,false),6);
        c.setOnClickListener(v->run.run());
        c.setFocusable(true);
        c.setContentDescription(title+"，"+subtitle);
        return c;
    }

    /**
     * 题库页：专题列表、实时关键词搜索、收藏入口和 JSON 导入入口。
     */
    private void bank(){
        heading("知识题库","按专题拆解嵌入式，学习有迹可循。");
        EditText search=new EditText(this);
        search.setSingleLine();
        search.setTextSize(14);
        search.setHint("搜索题目、知识点或解析");
        search.setPadding(dp(15),dp(12),dp(15),dp(12));
        search.setBackground(shape(Color.WHITE,14,LINE));
        add(page,search,20);
        LinearLayout content=vertical();
        add(page,content,8);
        renderCategories(content);
        // 输入变化就重新查询并替换列表；目前在主线程完成，题库变大时应考虑异步和输入防抖。
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int count,int after){
            }
            public void onTextChanged(CharSequence s,int st,int before,int count){
                content.removeAllViews();
                if(s.toString().trim().isEmpty())renderCategories(content);
                else renderQuestionRows(content,store.questions(null,s.toString().trim(),null),false);
            }
            public void afterTextChanged(Editable e){
            }
        });
        section("我的学习资料");
        add(page,action("查看收藏题目",false,()->listScreen("我的收藏",store.questions(null,"","bookmarks"),false)),12);
        add(page,action("导入 JSON 题库",true,this::importFile),10);
        add(page,text("支持 2～6 选 1 的单选题，每题包含答案与解析。相同 ID 自动跳过，已有题目不被覆盖。",12,MUTED,false),12);
    }

    /**
     * 根据数据库的分类统计生成卡片；点击分类进入 category 页面。
     * 图标数组只负责装饰，专题名称和题数来自数据库。
     */
    private void renderCategories(LinearLayout content){
        int i=0;
        for(Map.Entry<String,Integer> entry:store.categories().entrySet()){
            LinearLayout c=card(content,12);
            LinearLayout r=row();
            TextView icon=text(new String[]{
                "{ }","▣","◷","$ _","⇄","⌕"
            }
            [i++%6],21,BLUE,true);
            icon.setPadding(dp(8),dp(12),dp(8),dp(12));
            icon.setMinWidth(dp(58));
            icon.setBackground(shape(BLUE_SOFT,12,0));
            r.addView(icon);
            LinearLayout details=vertical();
            details.setPadding(dp(15),0,0,0);
            add(details,text(entry.getKey(),17,INK,true),0);
            add(details,text(entry.getValue()+" 道题 · 含答案解析",12,MUTED,false),7);
            weighted(r,details);
            r.addView(text("›",25,MUTED,false));
            add(c,r,0);
            c.setOnClickListener(v->category(entry.getKey()));
        }
    }

    /**
     * 显示一个专题，提供普通专题练习、限时配置和逐题查看入口。
     */
    private void category(String name){
        inQuiz=false;
        base("题库",false);
        back(()->showTab("题库"));
        heading(name,"理解原理，比记住答案更重要。");
        List<Question> qs=store.questions(name,"",null);
        add(page,action("专题练习 · "+qs.size()+" 题",true,()->startQuiz(qs,"专题练习",name,qs.size(),false)),22);
        add(page,action("配置专题测验",false,()->configure(name)),10);
        section("本专题全部题目");
        renderQuestionRows(page,qs,false);
    }

    /**
     * 创建页面内的“返回”控件。不同页面可传入不同的返回动作。
     */
    private void back(Runnable run){
        TextView t=text("‹  返回",14,BLUE,true);
        t.setMinHeight(dp(44));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setOnClickListener(v->run.run());
        add(page,t,0);
    }

    /**
     * 将题目列表显示为可点击卡片；mastery=true 时详情里允许标记已掌握。
     * 当前会为所有结果创建 View，尚未使用 RecyclerView 复用。
     */
    private void renderQuestionRows(LinearLayout parent,List<Question> qs,boolean mastery){
        if(qs.isEmpty()){
            empty(parent,"这里还没有题目","完成练习后回来看看，或导入自己的题库。");
            return;
        }
        int n=0;
        for(Question q:qs){
            LinearLayout c=card(parent,10);
            add(c,text(String.format(Locale.CHINA,"%02d  ·  %s",++n,q.category),11,BLUE,true),0);
            add(c,text(q.title,15,INK,true),10);
            add(c,text("查看题目与解析  →",12,MUTED,false),12);
            c.setOnClickListener(v->questionDetail(q,mastery));
        }
    }

    /**
     * 复用一套题目列表页，用于收藏等场景；根据 mastery 决定返回题库还是错题页。
     */
    private void listScreen(String title,List<Question> qs,boolean mastery){
        base("",false);
        back(()->showTab(mastery?"错题":"题库"));
        heading(title,"共 "+qs.size()+" 道题");
        renderQuestionRows(page,qs,mastery);
    }

    /**
     * 弹窗展示题目、全部选项、标准答案、解析和来源，并提供收藏／掌握操作。
     * 这是题库的学习查看功能，不是限时测验中的逐题答案展示。
     */
    private void questionDetail(Question q,boolean mastery){
        LinearLayout body=vertical();
        body.setPadding(dp(22),dp(12),dp(22),dp(22));
        add(body,text(q.category,12,BLUE,true),0);
        add(body,text(q.title,19,INK,true),14);
        for(int i=0;i<q.options.length;i++)add(body,text((char)('A'+i)+". "+q.options[i],15,INK,false),14);
        add(body,text("正确答案  "+(char)('A'+q.answer),16,BLUE,true),22);
        add(body,text(q.explanation,15,MUTED,false),12);
        if(!q.source.isEmpty())add(body,text("延伸阅读："+q.source,11,MUTED,false),16);
        // 用一个长度为 1 的数组保存弹窗引用，方便点击监听器创建后再取得已创建的弹窗并关闭它。
        final AlertDialog[] dialog=new AlertDialog[1];
        add(body,action(store.bookmarked(q.id)?"取消收藏":"收藏这道题",false,()->{
            store.toggleBookmark(q.id);
            toast(store.bookmarked(q.id)?"已收藏":"已取消收藏");
            dialog[0].dismiss();
        }),20);
        if(mastery)add(body,action("标记已掌握",true,()->{
            store.mastery(q.id,true);
            dialog[0].dismiss();
            showTab("错题");
        }),10);
        ScrollView scroll=new ScrollView(this);
        scroll.addView(body);
        dialog[0]=new AlertDialog.Builder(this).setView(scroll).setNegativeButton("关闭",null).create();
        dialog[0].show();
    }

    /**
     * 错题页：列出待巩固题目，支持重练、手动掌握和查看已掌握题目。
     */
    private void mistakes(){
        heading("错题，也是进步。","把每一次卡住，变成下一次的熟练。");
        long mastered=store.scalar("SELECT COUNT(*) FROM mistakes WHERE mastered=1",null);
        LinearLayout stats=row();
        metric(stats,String.valueOf(wrong()),"待巩固");
        metric(stats,String.valueOf(mastered),"已掌握");
        add(page,stats,26);
        List<Question> qs=store.questions(null,"","mistakes");
        if(qs.isEmpty())empty(page,"还没有待巩固的错题","完成一次练习或测验，错题会自动整理到这里。");
        else {
            add(page,action("开始错题重练 · "+qs.size()+" 题",true,()->startQuiz(qs,"错题重练","错题本",qs.size(),false)),25);
            add(page,text("重练答对会自动标记掌握；再次做错会重新加入。",12,MUTED,false),12);
            renderQuestionRows(page,qs,true);
        }
        if(mastered>0)add(page,action("查看已掌握题目",false,()->{
            base("",false);
            back(()->showTab("错题"));
            heading("已经掌握","需要时可以重新加入待巩固。");
            for(Question q:store.questions(null,"","mastered")){
                LinearLayout c=card(page,12);
                add(c,text(q.title,15,INK,true),0);
                add(c,action("重新加入错题本",false,()->{
                    store.mastery(q.id,false);
                    showTab("错题");
                }),14);
            }
        }),20);
    }

    /**
     * 显示无题目或无成绩时的空状态提示。
     */
    private void empty(LinearLayout parent,String title,String detail){
        LinearLayout c=card(parent,24);
        TextView icon=text("◇",44,BLUE,false);
        icon.setGravity(Gravity.CENTER);
        add(c,icon,10);
        TextView h=text(title,18,INK,true);
        h.setGravity(Gravity.CENTER);
        add(c,h,17);
        TextView d=text(detail,13,MUTED,false);
        d.setGravity(Gravity.CENTER);
        add(c,d,13);
    }

    /**
     * 弹出测验长度选项，然后调用 startQuiz。
     * 选 20 题但题池不足时，startQuiz 会截断题数，并按实际题数计算时长。
     */
    private void configure(String category){
        List<Question> qs=store.questions(category,"",null);
        String[] labels={
            "10 题 / 10 分钟","20 题 / 20 分钟","全部 "+qs.size()+" 题 / "+qs.size()+" 分钟"
        };
        new AlertDialog.Builder(this).setTitle("选择测验长度").setItems(labels,(d,w)->{
            int count=w==0?10:w==1?20:qs.size();
            startQuiz(qs,"模拟测验",category==null?"全部分类":category,count,true);
        }).setNegativeButton("取消",null).show();
    }

    /**
     * 从题目池创建一份新草稿，并进入答题。
     * pool：候选题；mode：练习模式；category：范围说明；count：请求题数；timed：是否限时。
     * 这是“抽题和初始化”的入口，不负责最终保存成绩。
     */
    private void startQuiz(List<Question> pool,String mode,String category,int count,boolean timed){
        // 没有题目就不创建测验；已有草稿时先让用户决定是否替换，避免无提示地覆盖答案。
        if(pool.isEmpty()){
            toast("暂无可练习的题目");
            return;
        }
        if(draft!=null){
            new AlertDialog.Builder(this).setTitle("还有未完成的练习").setMessage("开始新的练习会替换当前草稿。").setNegativeButton("保留草稿",null).setPositiveButton("开始新的",(d,w)->{
                draft=null;
                startQuiz(pool,mode,category,count,timed);
            }).show();
            return;
        }
        // 复制后再随机打乱，避免改动原题池；截取 min(count, 题池大小)，一次测验不重复选同一列表项。
        List<Question> chosen=new ArrayList<>(pool);
        Collections.shuffle(chosen);
        chosen=new ArrayList<>(chosen.subList(0,Math.min(count,chosen.size())));
        try {
            // 三个平行数组按相同下标对应同一题：questions 是题目快照，answers 是选择，checked 是普通练习确认状态。
            draft=new JSONObject();
            JSONArray questions=new JSONArray(),answers=new JSONArray(),checked=new JSONArray();
            // 复制完整题目内容，恢复时直接读这份快照，不重新随机抽题；答案 -1 表示未作答。
            for(Question q:chosen){
                questions.put(q.json());
                answers.put(-1);
                checked.put(false);
            }
            // 每次作答一个 UUID。started 和 deadline 单位为毫秒；普通练习 deadline=0，测验每道实际题目分配一分钟。
            long now=System.currentTimeMillis();
            draft.put("id",UUID.randomUUID().toString()).put("mode",mode).put("category",category).put("started",now).put("deadline",timed?now+chosen.size()*60000L:0).put("questions",questions).put("answers",answers).put("checked",checked).put("index",0);
            index=0;
            inQuiz=true;
            saveDraft();
            showQuestion();
        }catch(JSONException e){
            error(e);
        }
    }

    /**
     * 把草稿和当前题号保存到本 Activity 的 SharedPreferences。
     * draft=null 时删除草稿；否则序列化成 JSON 字符串。这里使用同步 commit，当前未处理其失败返回值。
     */
    private void saveDraft(){
        if(draft==null)getPreferences(0).edit().remove("draft").commit();
        else {
            try{
                draft.put("index",index);
            }catch(JSONException ignored){
            }
            getPreferences(0).edit().putString("draft",draft.toString()).commit();
        }
    }

    /**
     * 根据 draft 和 index 重建当前题的界面，绑定选项、确认、上一题、下一题、答题卡和收藏动作。
     * 用户一选择就保存答案；普通练习确认后锁定并展示解析，限时测验交卷前仍允许修改。
     */
    private void showQuestion(){
        if(draft==null)return;
        inQuiz=true;
        timer=null;
        base("",false);
        back(this::leaveQuiz);
        try {
            JSONArray questions=draft.getJSONArray("questions"),answers=draft.getJSONArray("answers"),checked=draft.getJSONArray("checked");
            Question q=Question.parse(questions.getJSONObject(index));
            // timed 判断模式；locked 只在普通练习确认后为 true；selected=-1 表示还没选择。
            boolean timed=draft.getLong("deadline")>0,locked=!timed && checked.getBoolean(index);
            int selected=answers.getInt(index);
            LinearLayout label=row();
            weighted(label,text(draft.getString("mode"),13,BLUE,true));
            timer=text(timed?"剩余 "+duration((QuizEngine.remaining(draft.getLong("deadline"),System.currentTimeMillis())+999)/1000):"不限时",12,MUTED,false);
            label.addView(timer);
            add(page,label,14);
            heading(String.format(Locale.CHINA,"%02d / %02d",index+1,questions.length()),q.category+" · 单选题");
            ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
            progress.setMax(questions.length());
            progress.setProgress(index+1);
            progress.setProgressTintList(android.content.res.ColorStateList.valueOf(BLUE));
            add(page,progress,18);
            LinearLayout question=card(page,18);
            add(question,text(q.title,20,INK,true),0);
            for(int i=0;i<q.options.length;i++) {
                // 监听器捕获每个选项的独立下标；不要直接依赖循环结束后会变化的 i。
                final int option=i;
                boolean correct=locked && i==q.answer,incorrect=locked && selected==i && i!=q.answer;
                int color=correct?BLUE:incorrect?RED:selected==i?BLUE:INK;
                TextView answer=text((char)('A'+i)+"   "+q.options[i],15,color,selected==i||correct);
                answer.setPadding(dp(16),dp(17),dp(16),dp(17));
                answer.setMinHeight(dp(56));
                answer.setBackground(shape(correct?BLUE_SOFT:incorrect?ERROR_SOFT:selected==i?BLUE_SOFT:Color.WHITE,13,correct?BLUE:incorrect?RED:selected==i?BLUE:LINE));
                // 尚未锁定才接受修改。先写 answers，再保存草稿，最后重新渲染选择样式。
                answer.setOnClickListener(v->{
                    if(!locked){
                        try{
                            answers.put(index,option);
                            saveDraft();
                            showQuestion();
                        }catch(JSONException e){
                            error(e);
                        }
                    }
                });
                add(page,answer,11);
            }
            // 普通练习确认后显示正确答案与解析；测验模式不会在此锁定并揭示答案。
            if(locked){
                LinearLayout explanation=card(page,16);
                add(explanation,text(selected==q.answer?"✓ 回答正确":"再理解一次，就更进一步",15,selected==q.answer?BLUE:RED,true),0);
                add(explanation,text("正确答案  "+(char)('A'+q.answer),14,BLUE,true),13);
                add(explanation,text(q.explanation,15,INK,false),10);
            }
            if(!timed && !locked)add(page,action("确认答案",true,()->{
                if(answers.optInt(index,-1)<0){
                    toast("请先选择一个答案");
                    return;
                }
                try{
                    checked.put(index,true);
                    saveDraft();
                    showQuestion();
                }catch(JSONException e){
                    error(e);
                }
            }),20);
            else add(page,action(index==questions.length()-1?"提交并查看成绩":"下一题   →",true,()->{
                if(index==questions.length()-1)confirmSubmit();
                else{
                    index++;
                    saveDraft();
                    showQuestion();
                }
            }),20);
            LinearLayout tools=row();
            TextView previous=action("上一题",false,()->{
                if(index>0){
                    index--;
                    saveDraft();
                    showQuestion();
                }
            });
            previous.setEnabled(index>0);
            previous.setAlpha(index>0?1:.4f);
            weighted(tools,previous);
            TextView sheet=action("答题卡",false,this::answerSheet);
            LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,-2,1);
            sp.leftMargin=dp(10);
            tools.addView(sheet,sp);
            add(page,tools,10);
            add(page,action(store.bookmarked(q.id)?"★ 已收藏 · 点击取消":"☆ 收藏这道题",false,()->{
                store.toggleBookmark(q.id);
                showQuestion();
            }),10);
        }catch(Exception e){
            error(e);
        }
    }

    /**
     * 按答案数组生成答题卡。-1 显示未作答；点题号会保存新 index 并刷新题目。
     */
    private void answerSheet(){
        try{
            JSONArray a=draft.getJSONArray("answers");
            String[] items=new String[a.length()];
            for(int i=0;i<a.length();i++)items[i]=String.format(Locale.CHINA,"第 %02d 题    %s",i+1,a.getInt(i)<0?"未作答":"已选 "+(char)('A'+a.getInt(i)));
            new AlertDialog.Builder(this).setTitle("答题卡").setItems(items,(d,w)->{
                index=w;
                saveDraft();
                showQuestion();
            }).setPositiveButton("交卷",(d,w)->confirmSubmit()).setNegativeButton("继续答题",null).show();
        }catch(JSONException e){
            error(e);
        }
    }

    /**
     * 统计未作答题数并弹出交卷确认。确认之后才调用 submit，取消则继续答题。
     */
    private void confirmSubmit(){
        try{
            int missing=0;
            JSONArray a=draft.getJSONArray("answers");
            for(int i=0;i<a.length();i++)if(a.getInt(i)<0)missing++;
            new AlertDialog.Builder(this).setTitle("确认交卷").setMessage(missing==0?"提交后将保存成绩并整理错题。":"还有 "+missing+" 题未作答，将按错误计入成绩和错题本。确认提交？").setNegativeButton("继续答题",null).setPositiveButton("交卷",(d,w)->submit()).show();
        }catch(JSONException e){
            error(e);
        }
    }

    /**
     * 界面层交卷入口：让 Store 保存结果，再清空草稿并展示成绩。
     * 必须先保存成功再清草稿；数据库抛出异常时交给 error 显示，原草稿仍在。
     */
    private void submit(){
        if(draft==null)return;
        try{
            JSONObject result=store.submit(draft);
            draft=null;
            inQuiz=false;
            saveDraft();
            result(result);
        }catch(Exception e){
            error(e);
        }
    }

    /**
     * 离开答题前给出选择。保存并返回只切到首页，不改 started／deadline，因此测验时间继续流逝。
     */
    private void leaveQuiz(){
        new AlertDialog.Builder(this).setTitle("暂时离开答题？").setMessage("答案会保留，可从首页继续。模拟测验的计时会继续。").setNegativeButton("继续答题",null).setPositiveButton("保存并返回",(d,w)->{
            saveDraft();
            showTab("首页");
        }).show();
    }

    /**
     * 显示一份已提交结果：总分、正确题数、用时，以及逐题答案和解析。
     * 传入的 r 可以来自刚交卷的结果，也可以来自历史记录；打开历史不重新计分或新增成绩。
     */
    private void result(JSONObject r){
        inQuiz=false;
        timer=null;
        base("",false);
        back(()->showTab("分析"));
        heading("每一次练习，都算数。",r.optString("mode")+" · "+r.optString("category"));
        LinearLayout c=card(page,24);
        TextView score=text(r.optInt("score")+"",60,BLUE,true);
        score.setGravity(Gravity.CENTER);
        add(c,score,12);
        TextView label=text("本次成绩 / 100 分",13,MUTED,false);
        label.setGravity(Gravity.CENTER);
        add(c,label,8);
        LinearLayout stats=row();
        int total=r.optJSONArray("questions").length();
        metric(stats,r.optInt("correct")+" / "+total,"答对题数");
        metric(stats,duration(r.optLong("duration")),"答题用时");
        add(c,stats,24);
        add(page,action("回到首页",true,()->showTab("首页")),20);
        add(page,action("去巩固错题",false,()->showTab("错题")),10);
        section("逐题回顾");
        try{
            JSONArray qs=r.getJSONArray("questions"),answers=r.getJSONArray("answers");
            for(int i=0;i<qs.length();i++){
                Question q=Question.parse(qs.getJSONObject(i));
                int a=answers.getInt(i);
                LinearLayout item=card(page,12);
                add(item,text((a==q.answer?"✓ 正确":"× "+(a<0?"未作答":"回答错误"))+"  ·  "+q.category,12,a==q.answer?BLUE:RED,true),0);
                add(item,text(q.title,16,INK,true),11);
                add(item,text("你的答案："+(a<0?"未作答":(char)('A'+a)+". "+q.options[a])+"\n正确答案："+(char)('A'+q.answer)+". "+q.options[q.answer],14,INK,false),13);
                add(item,text(q.explanation,14,MUTED,false),12);
            }
        }catch(JSONException e){
            error(e);
        }
    }

    /**
     * 遍历已提交历史，计算总题次、正确题次、最高分、分类正确率，并展示趋势和历史列表。
     * 未提交草稿不参加统计；重复练习会再次计入题次。
     */
    private void analytics(){
        heading("让进步，看得见。","每次提交后的真实数据，都记录在这里。");
        List<JSONObject> history=store.history();
        if(history.isEmpty()){
            empty(page,"完成第一份练习","交卷后即可查看成绩趋势、分类正确率和历史记录。");
            add(page,action("开始第一份练习",true,()->startQuiz(store.questions(null,"",null),"随机练习","全部分类",10,false)),20);
            return;
        }
        // count＝累计题次，right＝累计正确题次，best＝最高分，time＝累计秒数；cats 为分类统计。
        int count=0,right=0,best=0;
        long time=0;
        LinkedHashMap<String,int[]> cats=new LinkedHashMap<>();
        // 每个分类对应 int[2]：下标 0 为答对题次，下标 1 为总题次；按每道题的真实分类累计。
        for(JSONObject h:history){
            count+=h.optJSONArray("questions").length();
            right+=h.optInt("correct");
            best=Math.max(best,h.optInt("score"));
            time+=h.optLong("duration");
            try{
                JSONArray qs=h.getJSONArray("questions"),a=h.getJSONArray("answers");
                for(int i=0;i<qs.length();i++){
                    JSONObject q=qs.getJSONObject(i);
                    String cat=q.getString("category");
                    int[] sum=cats.get(cat);
                    if(sum==null){
                        sum=new int[2];
                        cats.put(cat,sum);
                    }
                    sum[1]++;
                    if(a.getInt(i)==q.getInt("answer"))sum[0]++;
                }
            }catch(JSONException e){
                error(e);
            }
        }
        LinearLayout top=row();
        metric(top,history.size()+"","已完成练习");
        metric(top,QuizEngine.score(right,count)+"%","累计正确率");
        metric(top,best+"","最高成绩");
        add(page,top,27);
        LinearLayout chart=card(page,23);
        add(chart,text("最近成绩趋势",17,INK,true),0);
        add(chart,text("最近 "+Math.min(7,history.size())+" 次提交 · 0～100 分",11,MUTED,false),8);
        chart.addView(new Trend(history),new LinearLayout.LayoutParams(-1,dp(180)));
        // 在已练分类中找正确率最低者作为建议；这是一条明确的规则，不是 AI 模型，也没有给未练专题估分。
        section("知识点掌握情况");
        String weak=null;
        double low=2;
        for(Map.Entry<String,int[]> entry:cats.entrySet()){
            int[] n=entry.getValue();
            int percent=QuizEngine.score(n[0],n[1]);
            if((double)n[0]/n[1]<low){
                low=(double)n[0]/n[1];
                weak=entry.getKey();
            }
            LinearLayout c=card(page,10);
            LinearLayout r=row();
            weighted(r,text(entry.getKey(),15,INK,true));
            r.addView(text(percent+"%",15,BLUE,true));
            add(c,r,0);
            ProgressBar p=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
            p.setMax(100);
            p.setProgress(percent);
            p.setProgressTintList(android.content.res.ColorStateList.valueOf(BLUE));
            add(c,p,12);
            add(c,text("答对 "+n[0]+" / "+n[1]+" 题次",11,MUTED,false),6);
        }
        if(weak!=null){
            final String focus=weak;
            LinearLayout c=card(page,15);
            add(c,text("下一步建议 · "+weak,15,BLUE,true),0);
            add(c,text("这是目前正确率最低的已练专题，再练一轮巩固概念。重复练习会计入统计。",12,MUTED,false),10);
            add(c,action("去练这个专题",false,()->category(focus)),15);
        }
        section("历史练习");
        for(JSONObject h:history){
            LinearLayout c=card(page,10);
            LinearLayout r=row();
            weighted(r,text(h.optString("mode")+" · "+h.optInt("score")+" 分",15,INK,true));
            r.addView(text("›",22,MUTED,false));
            add(c,r,0);
            add(c,text(date(h.optLong("time"))+"  ·  "+h.optJSONArray("questions").length()+" 题  ·  "+duration(h.optLong("duration")),12,MUTED,false),9);
            c.setOnClickListener(v->result(h));
        }
        add(page,action("导出全部成绩记录",false,()->exportFile(store.exportHistory().toString(),"embedded-results.json")),22);
        add(page,text("累计用时 "+duration(time)+"。统计包含练习、测验和错题重练；未提交草稿不计入。",11,MUTED,false),12);
    }
    // 成绩趋势自定义 View：使用 Canvas 和 Paint 直接画图，不依赖第三方图表库。
    private class Trend extends View {
        // scores 按时间从旧到新保存最近至多七次成绩；paint 用来设置线条、圆点和文字的样式。
        final List<Integer> scores=new ArrayList<>();
        final Paint paint=new Paint(3);
        // 历史传入时是新到旧，所以倒着取前七条；setContentDescription 为图表补一段文字描述。
        Trend(List<JSONObject> history){
            super(MainActivity.this);
            for(int i=Math.min(7,history.size())-1;i>=0;i--)scores.add(history.get(i).optInt("score"));
            setContentDescription("最近成绩趋势 "+scores);
        }
        // 绘制图表：先定绘图区，再画 0／50／100 参考线，最后画成绩点、连线和标签。
        protected void onDraw(Canvas c){
            super.onDraw(c);
            float left=dp(28),right=getWidth()-dp(15),top=dp(25),bottom=getHeight()-dp(30);
            paint.setTextSize(dp(10));
            paint.setStrokeWidth(dp(1));
            for(int value=0;value<=100;value+=50){
                float y=bottom-(bottom-top)*value/100;
                paint.setColor(LINE);
                c.drawLine(left,y,right,y,paint);
                paint.setColor(MUTED);
                c.drawText(""+value,0,y+dp(3),paint);
            }
            // 纵坐标＝底部－绘图区高度×分数／100。只有一个点时横坐标居中，避免按点数减一计算时除零。
            for(int i=0;i<scores.size();i++){
                float x=scores.size()==1?(left+right)/2:left+(right-left)*i/(scores.size()-1),y=bottom-(bottom-top)*scores.get(i)/100;
                paint.setColor(BLUE);
                paint.setStrokeWidth(dp(2));
                if(i>0){
                    float px=scores.size()==1?x:left+(right-left)*(i-1)/(scores.size()-1),py=bottom-(bottom-top)*scores.get(i-1)/100;
                    c.drawLine(px,py,x,y,paint);
                }
                c.drawCircle(x,y,dp(4),paint);
                paint.setTextAlign(Paint.Align.CENTER);
                c.drawText(scores.get(i)+"",x,y-dp(10),paint);
                paint.setColor(MUTED);
                c.drawText(""+(i+1),x,bottom+dp(20),paint);
                paint.setTextAlign(Paint.Align.LEFT);
            }
        }
    }

    /**
     * 调用系统文档选择器，选择要读取的题库。请求码 100 用来识别导入结果。
     * 选择器先允许选择文件，JSON 的实际结构由后续解析校验，不是只看扩展名。
     */
    private void importFile(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i,100);
    }

    /**
     * 先暂存导出内容，再调用系统创建文档界面。请求码 101 表示导出。
     * 内容同时保存一份到偏好设置，尽量应对打开文档界面期间 Activity 被重建。
     */
    private void exportFile(String body,String name){
        exportText=body;
        getPreferences(0).edit().putString("export",body).apply();
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.setType("application/json");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_TITLE,name);
        startActivityForResult(i,101);
    }

    /**
     * 处理系统文件界面的返回：100 导入，101 导出；取消或没有 URI 时直接返回。
     * 通过 ContentResolver 访问 URI 对应的流，不把 URI 强行当作本地文件路径。
     * 这是旧的结果回调方式，目前仍使用它；并不是已经迁移到了 Activity Result API。
     */
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK || data==null || data.getData()==null)return;
        try {
            if(request==100){
                try(InputStream in=getContentResolver().openInputStream(data.getData())){
                    int count=store.importQuestions(Store.read(in));
                    toast("成功导入 "+count+" 道题，重复 ID 已跳过");
                    showTab("题库");
                }
            }
            else if(request==101){
                String body=exportText.isEmpty()?getPreferences(0).getString("export",""):exportText;
                try(OutputStream out=getContentResolver().openOutputStream(data.getData())){
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
                getPreferences(0).edit().remove("export").apply();
                toast("记录已导出");
            }
        }catch(Exception e){
            new AlertDialog.Builder(this).setTitle("文件处理失败").setMessage(e.getMessage()).setPositiveButton("知道了",null).show();
        }
    }

    /**
     * 把秒数格式化为“分钟:秒”，例如 125 秒显示 02:05；分钟可超过 59。
     */
    private String duration(long seconds){
        return String.format(Locale.CHINA,"%02d:%02d",seconds/60,seconds%60);
    }

    /**
     * 将提交时的毫秒时间戳格式化成月日和时分，供历史列表显示。
     */
    private String date(long time){
        return new SimpleDateFormat("MM月dd日 HH:mm",Locale.CHINA).format(new Date(time));
    }

    /**
     * 显示短暂提示，适合收藏成功、导入完成等轻量反馈。
     */
    private void toast(String message){
        Toast.makeText(this,message,Toast.LENGTH_SHORT).show();
    }

    /**
     * 显示操作异常的消息，不把失败静默当成成功。
     */
    private void error(Exception e){
        new AlertDialog.Builder(this).setTitle("操作未完成").setMessage(e.getMessage()).setPositiveButton("知道了",null).show();
    }

    /**
     * 处理系统返回键：答题中先确认离开；其他栏目先回首页；首页交回系统默认处理。
     */
    @Override public void onBackPressed(){
        if(inQuiz)leaveQuiz();
        else if(!tab.equals("首页"))showTab("首页");
        else super.onBackPressed();
    }
}
