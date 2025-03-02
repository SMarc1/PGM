package tc.oc.pgm.command.parsers;

import static cloud.commandframework.arguments.parser.ArgumentParseResult.failure;
import static net.kyori.adventure.text.Component.text;
import static tc.oc.pgm.util.text.TextException.exception;
import static tc.oc.pgm.util.text.TextException.invalidFormat;
import static tc.oc.pgm.util.text.TextException.playerOnly;

import cloud.commandframework.arguments.parser.ArgumentParseResult;
import cloud.commandframework.arguments.parser.ParserParameters;
import cloud.commandframework.context.CommandContext;
import cloud.commandframework.paper.PaperCommandManager;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.bukkit.command.CommandSender;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.jetbrains.annotations.NotNull;
import tc.oc.pgm.api.match.Match;
import tc.oc.pgm.api.match.MatchModule;
import tc.oc.pgm.command.util.CommandKeys;
import tc.oc.pgm.command.util.CommandUtils;
import tc.oc.pgm.util.LiquidMetal;
import tc.oc.pgm.util.StringUtils;
import tc.oc.pgm.util.text.TextException;

/**
 * Generic parser for match-time objects, eg: teams, classes, modes, etc. Automatically handles
 * getting the module, and failing if module isn't enabled.
 *
 * @param <T> The object-type to parse
 * @param <M> The match module that provides it
 */
public abstract class MatchObjectParser<T, M extends MatchModule>
    extends StringLikeParser<CommandSender, T> {

  private final Class<T> objType;
  private final Class<M> moduleType;
  private final String moduleName;

  public MatchObjectParser(
      PaperCommandManager<CommandSender> manager,
      ParserParameters options,
      Class<T> objType,
      Class<M> moduleType,
      String moduleName) {
    super(manager, options);
    this.objType = objType;
    this.moduleType = moduleType;
    this.moduleName = moduleName;
  }

  @Override
  public ArgumentParseResult<T> parse(
      @NotNull CommandContext<CommandSender> context, @NotNull String text) {
    Match match = CommandUtils.getMatch(context);
    if (match == null) return failure(playerOnly());

    M module = match.getModule(moduleType);
    if (module == null) return failure(moduleNotFound());

    T obj = StringUtils.bestFuzzyMatch(text, objects(module), this::getName);
    if (obj == null) return failure(invalidFormat(text, objType));

    return ArgumentParseResult.success(obj);
  }

  @Override
  public @NonNull List<@NonNull String> suggestions(
      @NonNull CommandContext<CommandSender> context, @NonNull String input) {
    Match match = CommandUtils.getMatch(context);
    if (match == null) return Collections.emptyList();

    List<String> inputQueue = context.get(CommandKeys.INPUT_QUEUE);
    String text = StringUtils.getText(inputQueue);
    String mustKeep = StringUtils.getMustKeepText(inputQueue);

    return match.moduleOptional(moduleType).map(this::objects).orElse(Collections.emptySet())
        .stream()
        .map(this::getName)
        .filter(name -> LiquidMetal.match(name, text))
        .map(name -> StringUtils.getSuggestion(name, mustKeep))
        .collect(Collectors.toList());
  }

  protected abstract Collection<T> objects(M module);

  protected abstract String getName(T obj);

  protected TextException moduleNotFound() {
    return exception("command.moduleNotFound", text(moduleName));
  }
}
