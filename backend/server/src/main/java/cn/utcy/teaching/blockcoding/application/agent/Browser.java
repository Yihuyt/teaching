package cn.utcy.teaching.blockcoding.application.agent;

/** 提交时在用户浏览器里的编辑器上执行的动作;失败抛 IllegalStateException(中文原因) */
public interface Browser {
    /** 新建角色(默认造型);返回实际角色名 */
    String createSprite(String name);

    /** 把编译好的脚本插进作品(连同它新建的变量,私有的建在它的角色上);返回插入后的顶层积木 id */
    String insertScript(Script script);

    /** 从作品里删掉一段脚本(顶层积木 id);那段已经不在了也算成功 */
    void removeScript(String sprite, String blockId);

    /** 删掉一个变量 / 列表;sprite 为 null 是全局的 */
    void deleteVariable(String sprite, String name, boolean list);

    void deleteSprite(String name);
}
