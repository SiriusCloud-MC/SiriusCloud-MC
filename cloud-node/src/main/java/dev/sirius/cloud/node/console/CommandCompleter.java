package dev.sirius.cloud.node.console;

import dev.sirius.cloud.node.command.Command;
import dev.sirius.cloud.node.command.CommandManager;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;

/** Completes command names, then delegates to the command for its arguments. */
final class CommandCompleter implements Completer {

    private final CommandManager commands;

    CommandCompleter(CommandManager commands) {
        this.commands = commands;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        List<String> words = line.words();

        if (line.wordIndex() == 0) {
            commands.all().forEach(command ->
                    candidates.add(new Candidate(command.name(), command.name(), null,
                            command.description(), null, null, true)));
            return;
        }

        commands.find(words.get(0)).ifPresent(command -> {
            String[] args = words.subList(1, line.wordIndex()).toArray(String[]::new);
            command.complete(args).forEach(value -> candidates.add(new Candidate(value)));
        });
    }
}
