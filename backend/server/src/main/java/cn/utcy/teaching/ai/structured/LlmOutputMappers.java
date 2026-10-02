package cn.utcy.teaching.ai.structured;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 解析大模型输出的专用 mapper 工厂。
 *
 * 平台全局开启 FAIL_ON_MISSING_CREATOR_PROPERTIES(API 请求缺字段即失败),
 * 但模型输出的可选字段是**合法缺省**(讲稿段按规范不含 audioPath、编辑操作只给
 * 要改的字段等,可选性由 dsl schema 定义、业务校验在各 refine/apply)。
 * 解析模型输出一律经此工厂取宽松副本:缺省放行,未知字段仍拒绝。
 * 直接用全局 mapper 解析模型输出是已知陷阱——真机上讲稿/编辑解析会整批失败。
 */
public final class LlmOutputMappers {

    private LlmOutputMappers() {
    }

    public static ObjectMapper lenient(ObjectMapper base) {
        return base.copy().disable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
    }
}
