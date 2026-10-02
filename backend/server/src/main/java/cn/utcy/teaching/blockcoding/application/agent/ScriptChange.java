package cn.utcy.teaching.blockcoding.application.agent;

/**
 * 本轮对一段脚本的改动。script 是改动后的那段(删除时是被删的那段);
 * previous 是动手前就有的那段(新写为 null),回退时按它恢复。同一段改写多次只记最初的 previous。
 */
public record ScriptChange(Kind kind, Script script, Script previous) {
    public enum Kind { WRITTEN, REPLACED, DELETED }
}
