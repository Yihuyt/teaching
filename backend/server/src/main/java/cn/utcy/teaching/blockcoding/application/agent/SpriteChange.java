package cn.utcy.teaching.blockcoding.application.agent;

/**
 * 本轮对角色的改动。删掉作品里原有的角色(preexisting)时它的造型、声音是文件,回退恢复不了;
 * 删掉本轮新建的角色则整个改动撤销,不记。
 */
public record SpriteChange(Kind kind, Sprite sprite, boolean preexisting) {
    public enum Kind { CREATED, DELETED }
}
