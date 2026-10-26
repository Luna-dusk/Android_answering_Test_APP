package cn.embedded.quiz.checks;

import android.app.Instrumentation;
import android.os.Bundle;
import cn.embedded.quiz.*;
import org.json.*;
import java.util.*;

/** Runs only on the dedicated test emulator. Clears its own freshly installed app data first. */
public class DeviceChecks extends Instrumentation {
    private void check(boolean value,String name){if(!value)throw new AssertionError(name);}
    private JSONObject draft(List<Question> questions,int[] answers,String mode) throws Exception {
        JSONArray qs=new JSONArray(),as=new JSONArray();for(Question q:questions)qs.put(q.json());for(int a:answers)as.put(a);
        long now=System.currentTimeMillis();return new JSONObject().put("id",UUID.randomUUID().toString()).put("questions",qs).put("answers",as).put("mode",mode).put("category","自动检查").put("started",now-3000).put("deadline",0);
    }
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){Bundle result=new Bundle();Store store=null;try{
        getTargetContext().deleteDatabase("embedded.db");store=new Store(getTargetContext());
        check(store.questions(null,"",null).size()==72,"Seed count");check(store.categories().size()==6,"Category count");
        Question first=store.questions(null,"",null).get(0);check(store.questions(null,"volatile",null).size()>=2,"Search explanation");
        store.toggleBookmark(first.id);check(store.bookmarked(first.id),"Bookmark set");store.toggleBookmark(first.id);check(!store.bookmarked(first.id),"Bookmark unset");
        JSONObject imported=first.json();imported.put("id","test-import-1");String valid=new JSONObject().put("version",1).put("questions",new JSONArray().put(imported)).toString();
        check(store.importQuestions(valid)==1,"Import valid");check(store.importQuestions(valid)==0,"Import duplicate skipped");
        JSONObject pending=first.json().put("id","should-never-exist"),bad=first.json().put("id","bad").put("answer",99);
        boolean rejected=false;try{store.importQuestions(new JSONObject().put("version",1).put("questions",new JSONArray().put(pending).put(bad)).toString());}catch(JSONException e){rejected=true;}
        check(rejected && store.scalar("SELECT COUNT(*) FROM questions WHERE id='should-never-exist'",null)==0,"Import all-or-nothing");
        List<Question> qs=store.questions(null,"",null).subList(0,2);JSONObject attempt=draft(qs,new int[]{-1,(qs.get(1).answer+1)%4},"模拟测验");
        JSONObject scored=store.submit(attempt);check(scored.getInt("score")==0,"Missing answers scored wrong");check(store.questions(null,"","mistakes").size()==2,"Mistakes captured");
        store.submit(attempt);check(store.history().size()==1 && store.scalar("SELECT SUM(misses) FROM mistakes",null)==2,"Submission idempotent");
        store.close();store=new Store(getTargetContext());check(store.history().size()==1 && store.questions(null,"","mistakes").size()==2,"Persistence after reopen");
        store.submit(draft(qs,new int[]{qs.get(0).answer,qs.get(1).answer},"错题重练"));check(store.questions(null,"","mistakes").isEmpty(),"Correct review mastered");
        store.submit(draft(qs,new int[]{-1,-1},"随机练习"));check(store.questions(null,"","mistakes").size()==2,"Wrong again restores mistakes");
        check(store.exportHistory().length()==3,"History export");
        result.putString("stream","PASS: seed, search, bookmarks, import, rollback, scoring, idempotency, persistence, mastery, relapse, export (15 checks)\n");finish(-1,result);
    }catch(Throwable ex){result.putString("stream","FAIL: "+ex+"\n");finish(0,result);}finally{if(store!=null)store.close();}}
}
