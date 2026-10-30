package cn.embedded.quiz;

import org.json.*;


public final class Question {
    // id：唯一题号；category：专题；title：题干；explanation：解析；source：可选参考来源。
    public String id, category, title, explanation, source;
    public String[] options;
    // 正确答案的数组下标，从 0 开始；不是界面上显示的 A／B／C 字符。
    public int answer;

    public static Question parse(JSONObject j) throws JSONException {
        Question q = new Question();
        q.id = required(j,"id",100);
        q.category = required(j,"category",40);
        q.title = required(j,"title",2000);
        q.explanation = required(j,"explanation",5000);
        // 来源不是必填项，缺少时使用空字符串。
        q.source = j.optString("source", "");
        // 当前只支持单选题，允许 2～6 个选项；每个选项也会检查空内容和长度。
        JSONArray o = j.getJSONArray("options");
        if(o.length()<2 || o.length()>6) throw new JSONException("每题需有 2～6 个选项");
        q.options = new String[o.length()];
        for(int i=0;i<o.length();i++) {
            q.options[i]=o.getString(i).trim();
            if(q.options[i].isEmpty() || q.options[i].length()>2000) throw new JSONException("选项为空或过长");
        }
        // 先检查答案是数值且没有小数部分，再检查下标范围。
        Object answer = j.get("answer");
        if(!(answer instanceof Number) || ((Number)answer).doubleValue()!=((Number)answer).intValue()) throw new JSONException("answer 必须为整数");
        q.answer=((Number)answer).intValue();
        if(q.answer<0 || q.answer>=o.length()) throw new JSONException("answer 超出选项范围（从 0 开始）");
        if(q.source.length()>2000) throw new JSONException("source 过长");
        return q;
    }

    /**
     * 同一规则供题号、分类、题干、解析复用，避免每个字段重复写校验。
     */
    private static String required(JSONObject j,String key,int max) throws JSONException {
        String value=j.getString(key).trim();
        if(value.isEmpty() || value.length()>max) throw new JSONException(key+" 为空或过长");
        return value;
    }

    /**
     * 将题目转成 JSON 对象，供数据库 body、草稿快照和导出数据使用。
     */
    public JSONObject json() {
        JSONObject j=new JSONObject();
        try {
            j.put("id",id).put("category",category).put("title",title).put("options",new JSONArray(options)).put("answer",answer).put("explanation",explanation).put("source",source);
        } catch(JSONException e) {
            throw new IllegalStateException(e);
        }
        return j;
    }
}
