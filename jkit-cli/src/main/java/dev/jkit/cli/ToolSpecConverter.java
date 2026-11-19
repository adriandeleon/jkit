package dev.jkit.cli;

import dev.jkit.model.ToolSpec;
import picocli.CommandLine.ITypeConverter;

final class ToolSpecConverter implements ITypeConverter<ToolSpec> {
    @Override
    public ToolSpec convert(String value) {
        return ToolSpec.parse(value);
    }
}
