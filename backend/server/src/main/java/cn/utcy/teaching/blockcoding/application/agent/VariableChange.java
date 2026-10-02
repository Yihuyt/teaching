package cn.utcy.teaching.blockcoding.application.agent;

public record VariableChange(Kind kind, String name, boolean list, String sprite) {
    public enum Kind { CREATED, DELETED }

    public boolean global() {
        return sprite == null;
    }
}
