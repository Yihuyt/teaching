package cn.utcy.teaching.shared.web;

import java.beans.PropertyEditorSupport;
import java.util.function.Function;

public final class StrictContractPropertyEditor<T> extends PropertyEditorSupport {

    private final Function<String, T> parser;

    public StrictContractPropertyEditor(Function<String, T> parser) {
        this.parser = parser;
    }

    @Override
    public void setAsText(String text) {
        setValue(parser.apply(text));
    }
}
