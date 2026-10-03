package cn.utcy.teaching.blockcoding.domain;

import cn.utcy.teaching.shared.error.BadRequestException;

import java.util.List;

/** 积木编程助手可选的大模型:都支持工具调用;助手不开思考,所以不含纯思考型模型 */
public final class BlockCodingModels {

    public static final String DEFAULT = "qwen-plus";
    public static final List<String> ALL = List.of(
            "qwen-plus", "qwen-max", "qwen3-max", "qwen-turbo", "deepseek-v3", "kimi-k2.6", "kimi-k2.5");

    private BlockCodingModels() {
    }

    public static String require(String model) {
        if (model == null || !ALL.contains(model)) {
            throw new BadRequestException("请选择列表中的模型");
        }
        return model;
    }
}
