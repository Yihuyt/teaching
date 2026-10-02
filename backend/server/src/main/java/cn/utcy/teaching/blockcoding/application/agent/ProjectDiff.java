package cn.utcy.teaching.blockcoding.application.agent;

import java.util.List;

/**
 * 一轮做完后要写进编辑器的全部改动,按落地顺序:先建角色,再按顺序删旧脚本插新脚本(新建的变量随脚本插入时建),
 * 再删变量 / 列表,最后删角色(连同它的脚本一起没了)。
 */
public record ProjectDiff(List<Sprite> createdSprites, List<ScriptChange> scripts, List<VariableChange> deletedVariables,
                          List<SpriteChange> deletedSprites, List<VariableChange> createdVariables) {

    public boolean isEmpty() {
        return createdSprites.isEmpty() && scripts.isEmpty() && deletedVariables.isEmpty() && deletedSprites.isEmpty();
    }
}
