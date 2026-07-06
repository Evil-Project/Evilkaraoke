package org.evilproject.evilkaraoke.server.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CommandMessage {
    private final List<Part> parts;

    private CommandMessage(List<Part> parts) {
        this.parts = List.copyOf(parts);
    }

    public static CommandMessage text(String text) {
        return builder().append(text).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<Part> parts() {
        return parts;
    }

    public String plainText() {
        StringBuilder text = new StringBuilder();
        for (Part part : parts) {
            text.append(part.text());
        }
        return text.toString();
    }

    public record Part(String text, String command, String hoverText) {
        public Part {
            Objects.requireNonNull(text, "text");
        }

        public boolean clickable() {
            return command != null && !command.isBlank();
        }
    }

    public static final class Builder {
        private final List<Part> parts = new ArrayList<>();

        private Builder() {
        }

        public Builder append(String text) {
            parts.add(new Part(text, null, null));
            return this;
        }

        public Builder action(String text, String command, String hoverText) {
            parts.add(new Part(text, command, hoverText));
            return this;
        }

        public CommandMessage build() {
            return new CommandMessage(parts);
        }
    }
}
