import cn.embedded.quiz.QuizEngine;
public class QuizEngineCheck {
    static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
    public static void main(String[] args) {
        check(QuizEngine.correct(new int[]{0,1,2},new int[]{0,-1,2})==2,"Unanswered question must be wrong");
        check(QuizEngine.score(2,3)==67,"Fractional score rounded");
        check(QuizEngine.score(0,10)==0 && QuizEngine.score(10,10)==100,"Score endpoints");
        check(QuizEngine.remaining(5000,6000)==0,"Expired timer must stop at zero");
        check(QuizEngine.remaining(5000,3000)==2000,"Timer must use deadline, not render ticks");
        boolean rejected=false;try{QuizEngine.score(1,0);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"Empty quiz rejected");
        System.out.println("6 scoring and deadline checks passed");
    }
}
