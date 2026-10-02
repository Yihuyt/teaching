package cn.utcy.teaching.blockcoding.application.agent;

import cn.utcy.teaching.blockcoding.application.HarvestPayload;
import cn.utcy.teaching.blockcoding.engine.ProcedureXml;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Known;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Procedure;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.ProjectNames;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一轮运行里唯一的作品工作台:角色(造型、声音、私有变量 / 列表)、全局变量 / 列表 / 广播、编了号的脚本,
 * 以及本轮的全部改动记录。修改模式开局由编辑器快照装满;讲解模式没有作品,工作台是空的(只带之前回复里定义过的自定义积木);
 * 模型的每个工具只改这里,final_answer 之后按 {@link #diff()} 一次性写进编辑器。
 */
public final class Project {
    private final List<Sprite> sprites = new ArrayList<>();
    private final Set<String> globalVariables = new LinkedHashSet<>();
    private final Set<String> globalLists = new LinkedHashSet<>();
    private final Set<String> broadcasts = new LinkedHashSet<>();
    private final Map<String, List<Procedure>> snapshotProcedures = new HashMap<>();
    private final List<Procedure> inheritedProcedures = new ArrayList<>();
    private final Set<String> preexistingSprites = new LinkedHashSet<>();
    private final String currentSprite;

    private final List<Script> scripts = new ArrayList<>();
    private final Map<Integer, ScriptChange> scriptChanges = new LinkedHashMap<>();
    private final List<SpriteChange> spriteChanges = new ArrayList<>();
    private final List<VariableChange> variableChanges = new ArrayList<>();
    private int nextId;

    private final Map<String, Integer> failures = new HashMap<>();
    /** 本轮撤掉的脚本正文:紧接着原样重写等于白忙,要点破 */
    private final List<String> withdrawn = new ArrayList<>();
    private int unchangedResubmits;
    private boolean finishChecked;
    private boolean wrappingUp;
    private Question question;
    private String finalAnswer;

    private Project(String currentSprite) {
        this.currentSprite = currentSprite;
    }

    public static Project fromHarvest(HarvestPayload harvest, List<Script> existing) {
        Project project = new Project(harvest.currentSprite());
        if (harvest.sprites() != null) {
            for (HarvestPayload.SpriteContext context : harvest.sprites()) {
                project.sprites.add(new Sprite(context.name(), context.isStage(), context.costumes(), context.sounds(),
                        context.localVariables(), context.localLists()));
                project.preexistingSprites.add(context.name());
            }
        }
        addAll(project.globalVariables, harvest.globalVariables());
        addAll(project.globalLists, harvest.globalLists());
        addAll(project.broadcasts, harvest.broadcasts());
        if (harvest.procedures() != null) {
            harvest.procedures().forEach((sprite, signatures) -> project.snapshotProcedures.put(sprite, signatures.stream()
                    .map(s -> new Procedure(s.proccode(), s.argumentnames(), s.argumentids())).toList()));
        }
        project.seed(existing);
        return project;
    }

    public static Project empty() {
        Project project = new Project(null);
        project.seed(List.of());
        return project;
    }

    public Project inheritProcedures(List<Procedure> procedures) {
        inheritedProcedures.addAll(procedures);
        return this;
    }

    public List<Procedure> inheritedProcedures() {
        return List.copyOf(inheritedProcedures);
    }

    public boolean isEmpty() {
        return sprites.isEmpty() && globalVariables.isEmpty() && globalLists.isEmpty() && broadcasts.isEmpty() && scripts.isEmpty();
    }

    private void seed(List<Script> existing) {
        if (existing != null) {
            scripts.addAll(existing);
        }
        nextId = scripts.stream().mapToInt(Script::id).max().orElse(0) + 1;
    }

    private static void addAll(Set<String> target, List<String> values) {
        if (values != null) {
            target.addAll(values);
        }
    }

    // ---- 角色 ----------------------------------------------------------------

    public List<Sprite> sprites() {
        return List.copyOf(sprites);
    }

    public Sprite sprite(String name) {
        return sprites.stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
    }

    public String currentSprite() {
        return currentSprite;
    }

    public boolean preexistingSprite(String name) {
        return preexistingSprites.contains(name);
    }

    /** 造型和声音复制自作品里第一个角色(编辑器新建角色也是这么做的) */
    public Sprite createSprite(String name) {
        Sprite template = sprites.stream().filter(s -> !s.isStage()).findFirst().orElse(null);
        Sprite sprite = new Sprite(name, false, template == null ? List.of() : template.costumes(),
                template == null ? List.of() : template.sounds(), List.of(), List.of());
        return createSprite(sprite);
    }

    public Sprite createSprite(Sprite sprite) {
        sprites.add(sprite);
        spriteChanges.add(new SpriteChange(SpriteChange.Kind.CREATED, sprite, false));
        return sprite;
    }

    public List<Sprite> createdSprites() {
        return spriteChanges.stream().filter(c -> c.kind() == SpriteChange.Kind.CREATED).map(SpriteChange::sprite).toList();
    }

    public List<Script> deleteSprite(String name) {
        Sprite sprite = sprite(name);
        if (sprite == null) {
            return List.of();
        }
        List<Script> removed = scriptsOf(name);
        removed.forEach(this::delete);
        sprites.remove(sprite);
        for (String variable : sprite.localVariables()) {
            recordVariableDeleted(variable, false, name);
        }
        for (String list : sprite.localLists()) {
            recordVariableDeleted(list, true, name);
        }
        boolean created = spriteChanges.removeIf(c -> c.kind() == SpriteChange.Kind.CREATED && c.sprite().name().equals(name));
        if (!created) {
            spriteChanges.add(new SpriteChange(SpriteChange.Kind.DELETED, sprite, preexistingSprites.contains(name)));
        }
        return removed;
    }

    // ---- 变量、列表、广播 -------------------------------------------------------

    public Set<String> globalVariables() {
        return Set.copyOf(globalVariables);
    }

    public Set<String> globalLists() {
        return Set.copyOf(globalLists);
    }

    public Set<String> broadcasts() {
        return Set.copyOf(broadcasts);
    }

    public Set<String> variablesVisibleIn(String spriteName) {
        Set<String> out = new LinkedHashSet<>(globalVariables);
        Sprite sprite = sprite(spriteName);
        if (sprite != null) {
            out.addAll(sprite.localVariables());
        }
        return out;
    }

    public Set<String> listsVisibleIn(String spriteName) {
        Set<String> out = new LinkedHashSet<>(globalLists);
        Sprite sprite = sprite(spriteName);
        if (sprite != null) {
            out.addAll(sprite.localLists());
        }
        return out;
    }

    public boolean nameExists(String name, boolean list) {
        if ((list ? globalLists : globalVariables).contains(name)) {
            return true;
        }
        return sprites.stream().anyMatch(s -> (list ? s.localLists() : s.localVariables()).contains(name));
    }

    public void createVariable(String name, boolean list, String spriteName) {
        if (spriteName == null) {
            if ((list ? globalLists : globalVariables).add(name)) {
                variableChanges.add(new VariableChange(VariableChange.Kind.CREATED, name, list, null));
            }
            return;
        }
        Sprite sprite = sprite(spriteName);
        if (sprite == null) {
            return;
        }
        Sprite updated = list ? sprite.withLocalList(name) : sprite.withLocalVariable(name);
        if (updated != sprite) {
            sprites.set(sprites.indexOf(sprite), updated);
            variableChanges.add(new VariableChange(VariableChange.Kind.CREATED, name, list, spriteName));
        }
    }

    /**
     * 这个名字存在于哪些地方:null 是全局的,其余是私有它的角色名。同名可以同时出现在全局和几个角色里(Scratch 允许),
     * 角色自己有私有的时,它的脚本用的是私有那份
     */
    public List<String> holdersOf(String name, boolean list) {
        List<String> holders = new ArrayList<>();
        if ((list ? globalLists : globalVariables).contains(name)) {
            holders.add(null);
        }
        sprites.stream().filter(s -> (list ? s.localLists() : s.localVariables()).contains(name)).forEach(s -> holders.add(s.name()));
        return holders;
    }

    /** 还在用这份名字的脚本:owner 为 null 是全局那份(只算自己没有同名私有的角色的脚本),否则是那个角色私有的那份(只算它自己的脚本) */
    public List<Script> usersOf(String name, boolean list, String owner) {
        if (owner != null) {
            Sprite holder = sprite(owner);
            if (holder == null || !(list ? holder.localLists() : holder.localVariables()).contains(name)) {
                return List.of();
            }
        }
        return scripts.stream().filter(script -> {
            if (owner == null) {
                Sprite sprite = sprite(script.sprite());
                if (sprite != null && (list ? sprite.localLists() : sprite.localVariables()).contains(name)) {
                    return false;
                }
            } else if (!script.sprite().equals(owner)) {
                return false;
            }
            ScriptFacts facts = ScriptFacts.of(script.xml(), script.sprite());
            return (list ? facts.lists() : facts.variables()).contains(name);
        }).toList();
    }

    public List<Script> usersOf(String name, boolean list) {
        return scripts.stream().filter(script -> {
            ScriptFacts facts = ScriptFacts.of(script.xml(), script.sprite());
            return (list ? facts.lists() : facts.variables()).contains(name);
        }).toList();
    }

    public boolean deleteVariable(String name, boolean list) {
        if (!usersOf(name, list).isEmpty()) {
            return false;
        }
        for (String owner : holdersOf(name, list)) {
            deleteVariable(name, list, owner);
        }
        return true;
    }

    public boolean deleteVariable(String name, boolean list, String owner) {
        if (!usersOf(name, list, owner).isEmpty()) {
            return false;
        }
        if (owner == null) {
            if ((list ? globalLists : globalVariables).remove(name)) {
                recordVariableDeleted(name, list, null);
            }
            return true;
        }
        Sprite sprite = sprite(owner);
        if (sprite != null && (list ? sprite.localLists() : sprite.localVariables()).contains(name)) {
            sprites.set(sprites.indexOf(sprite), list ? sprite.withoutLocalList(name) : sprite.withoutLocalVariable(name));
            recordVariableDeleted(name, list, owner);
        }
        return true;
    }

    private void recordVariableDeleted(String name, boolean list, String spriteName) {
        boolean created = variableChanges.removeIf(c -> c.kind() == VariableChange.Kind.CREATED && c.name().equals(name)
                && c.list() == list && java.util.Objects.equals(c.sprite(), spriteName));
        if (!created) {
            variableChanges.add(new VariableChange(VariableChange.Kind.DELETED, name, list, spriteName));
        }
    }

    // ---- 脚本 ----------------------------------------------------------------

    public List<Script> all() {
        return List.copyOf(scripts);
    }

    public List<Script> scriptsOf(String sprite) {
        return scripts.stream().filter(s -> s.sprite().equals(sprite)).toList();
    }

    public Script find(int id) {
        return scripts.stream().filter(script -> script.id() == id).findFirst().orElse(null);
    }

    public int nextId() {
        return nextId;
    }

    public List<ScriptChange> changes() {
        return List.copyOf(scriptChanges.values());
    }

    public ScriptChange changeOf(int id) {
        return scriptChanges.get(id);
    }

    public void add(Script script) {
        scripts.add(script);
        nextId++;
        scriptChanges.put(script.id(), new ScriptChange(ScriptChange.Kind.WRITTEN, script, null));
        registerNames(script);
    }

    public void replace(Script old, Script updated) {
        scripts.set(scripts.indexOf(old), updated);
        ScriptChange prior = scriptChanges.get(updated.id());
        scriptChanges.put(updated.id(), prior == null ? new ScriptChange(ScriptChange.Kind.REPLACED, updated, old)
                : new ScriptChange(prior.kind(), updated, prior.previous()));
        registerNames(updated);
    }

    public ScriptChange delete(Script target) {
        scripts.remove(target);
        ScriptChange prior = scriptChanges.remove(target.id());
        if (prior == null) {
            return record(new ScriptChange(ScriptChange.Kind.DELETED, target, target));
        }
        if (prior.kind() == ScriptChange.Kind.REPLACED) {
            return record(new ScriptChange(ScriptChange.Kind.DELETED, target, prior.previous()));
        }
        withdrawn.add(target.code());
        // 本轮写的脚本撤掉了,它单独新建的名字也跟着撤:编辑器里从来没建过它们
        withdrawUnusedNames(target);
        return null;
    }

    private void withdrawUnusedNames(Script script) {
        for (String name : script.variables()) {
            if (usersOf(name, false).isEmpty() && variableChanges.removeIf(c -> c.kind() == VariableChange.Kind.CREATED && c.name().equals(name) && !c.list() && c.sprite() == null)) {
                globalVariables.remove(name);
            }
        }
        for (String name : script.lists()) {
            if (usersOf(name, true).isEmpty() && variableChanges.removeIf(c -> c.kind() == VariableChange.Kind.CREATED && c.name().equals(name) && c.list() && c.sprite() == null)) {
                globalLists.remove(name);
            }
        }
        Sprite owner = sprite(script.sprite());
        if (owner == null) {
            return;
        }
        for (String name : script.localVariables()) {
            if (usersOf(name, false).isEmpty() && variableChanges.removeIf(c -> c.kind() == VariableChange.Kind.CREATED && c.name().equals(name) && !c.list() && script.sprite().equals(c.sprite()))) {
                owner = owner.withoutLocalVariable(name);
            }
        }
        for (String name : script.localLists()) {
            if (usersOf(name, true).isEmpty() && variableChanges.removeIf(c -> c.kind() == VariableChange.Kind.CREATED && c.name().equals(name) && c.list() && script.sprite().equals(c.sprite()))) {
                owner = owner.withoutLocalList(name);
            }
        }
        int index = sprites.indexOf(sprite(script.sprite()));
        if (index >= 0) {
            sprites.set(index, owner);
        }
    }

    private ScriptChange record(ScriptChange change) {
        scriptChanges.put(change.script().id(), change);
        return change;
    }

    private void registerNames(Script script) {
        script.variables().forEach(name -> createVariable(name, false, null));
        script.lists().forEach(name -> createVariable(name, true, null));
        script.localVariables().forEach(name -> createVariable(name, false, script.sprite()));
        script.localLists().forEach(name -> createVariable(name, true, script.sprite()));
        broadcasts.addAll(script.broadcasts());
    }

    public Script identicalTo(String sprite, String body) {
        return scripts.stream().filter(s -> s.sprite().equals(sprite) && ScriptText.sameLines(s.code(), body)).findFirst().orElse(null);
    }

    public Script definitionOf(String sprite, List<String> proccodes) {
        return scripts.stream()
                .filter(s -> s.sprite().equals(sprite) && s.definedProcedures().stream().anyMatch(proccodes::contains))
                .findFirst().orElse(null);
    }

    /** 同一个角色里帽子相同的另一段脚本;别的角色有同样的帽子是正常的(每个角色各有自己的绿旗脚本),不算 */
    public Script sameHatAs(Script written) {
        String hat = ScriptText.firstLine(written.code());
        return scripts.stream()
                .filter(s -> s.id() != written.id() && s.sprite().equals(written.sprite()) && ScriptText.firstLine(s.code()).equals(hat))
                .findFirst().orElse(null);
    }

    public boolean withdrawnBefore(String body) {
        return withdrawn.stream().anyMatch(previous -> ScriptText.sameLines(previous, body));
    }

    public int failedAgain(String sprite, String body) {
        return failures.merge(sprite + "\n" + body.strip().replaceAll("\\s+", " "), 1, Integer::sum);
    }

    public int unchangedResubmit() {
        return ++unchangedResubmits;
    }

    public void enterWrapUp() {
        this.wrappingUp = true;
    }

    public boolean wrappingUp() {
        return wrappingUp;
    }

    public boolean firstFinishCheck() {
        if (finishChecked) {
            return false;
        }
        finishChecked = true;
        return true;
    }

    public void finish(String reply) {
        this.finalAnswer = reply;
    }

    public String finalAnswer() {
        return finalAnswer;
    }

    public void ask(Question question) {
        this.question = question;
    }

    public Question question() {
        return question;
    }

    // ---- 编译时的已知名字 ------------------------------------------------------

    public List<Procedure> proceduresOf(String spriteName) {
        List<Procedure> out = new ArrayList<>();
        scriptsOf(spriteName).forEach(script -> out.addAll(ProcedureXml.definitions(script.xml())));
        for (Procedure signature : snapshotProcedures.getOrDefault(spriteName, List.of())) {
            if (out.stream().noneMatch(p -> p.proccode().equals(signature.proccode()))) {
                out.add(signature);
            }
        }
        for (Procedure inherited : inheritedProcedures) {
            if (out.stream().noneMatch(p -> p.proccode().equals(inherited.proccode()))) {
                out.add(inherited);
            }
        }
        return out;
    }

    public Known knownFor(String spriteName, boolean validateNames) {
        Sprite target = sprite(spriteName);
        Map<String, String> elsewhere = new LinkedHashMap<>();
        for (Sprite other : sprites) {
            if (!other.name().equals(spriteName)) {
                proceduresOf(other.name()).stream()
                        .filter(p -> inheritedProcedures.stream().noneMatch(i -> i.proccode().equals(p.proccode())))
                        .forEach(p -> elsewhere.putIfAbsent(p.proccode(), other.name()));
            }
        }
        ProjectNames names = null;
        if (validateNames) {
            Set<String> spriteNames = new LinkedHashSet<>();
            Sprite stage = null;
            for (Sprite sprite : sprites) {
                if (sprite.isStage()) {
                    stage = sprite;
                } else {
                    spriteNames.add(sprite.name());
                }
            }
            names = new ProjectNames(spriteNames, new LinkedHashSet<>(target == null ? List.of() : target.costumes()),
                    new LinkedHashSet<>(stage == null ? List.of() : stage.costumes()),
                    new LinkedHashSet<>(target == null ? List.of() : target.sounds()));
        }
        // 别的角色私有的名字:这个角色看不到,读到就是错;和这里能看到的同名时以看得到的为准
        Map<String, String> privateVariables = new LinkedHashMap<>();
        Map<String, String> privateLists = new LinkedHashMap<>();
        Set<String> visibleVariables = variablesVisibleIn(spriteName);
        Set<String> visibleLists = listsVisibleIn(spriteName);
        for (Sprite other : sprites) {
            if (other.name().equals(spriteName)) {
                continue;
            }
            other.localVariables().stream().filter(v -> !visibleVariables.contains(v)).forEach(v -> privateVariables.putIfAbsent(v, other.name()));
            other.localLists().stream().filter(v -> !visibleLists.contains(v)).forEach(v -> privateLists.putIfAbsent(v, other.name()));
        }
        return new Known(visibleVariables, visibleLists, proceduresOf(spriteName), names, elsewhere)
                .withPrivateElsewhere(privateVariables, privateLists);
    }

    // ---- 提交 ----------------------------------------------------------------

    public ProjectDiff diff() {
        return new ProjectDiff(createdSprites(), changes(),
                variableChanges.stream().filter(c -> c.kind() == VariableChange.Kind.DELETED).toList(),
                spriteChanges.stream().filter(c -> c.kind() == SpriteChange.Kind.DELETED).toList(),
                variableChanges.stream().filter(c -> c.kind() == VariableChange.Kind.CREATED).toList());
    }

    // ---- 给模型看的样子 ------------------------------------------------------------

    public void describe(StringBuilder sb, String title) {
        sb.append("## ").append(title).append("(编号只在这一轮有效)\n");
        if (sprites.isEmpty()) {
            sb.append("- 还没有角色\n");
        }
        for (Sprite sprite : sprites) {
            describeSprite(sb, sprite, false);
        }
        describeGlobals(sb);
        if (currentSprite != null) {
            sb.append("- 用户此刻在编辑器里选中的角色:").append(currentSprite).append('\n');
        }
    }

    public String summary(String title, String preexistingNote) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(title).append('\n');
        if (sprites.isEmpty()) {
            sb.append("- 还没有角色\n");
        }
        for (Sprite sprite : sprites) {
            describeSprite(sb, sprite, true);
            for (Script script : scriptsOf(sprite.name())) {
                ScriptChange change = scriptChanges.get(script.id());
                sb.append("- #").append(script.id()).append(' ').append(ScriptText.firstLine(script.code()))
                        .append("(").append(script.blockCount()).append(" 个积木,")
                        .append(change == null ? preexistingNote : change.kind() == ScriptChange.Kind.REPLACED ? "本轮改写" : "本轮新写")
                        .append(")\n");
            }
        }
        describeGlobals(sb);
        return sb.toString().stripTrailing();
    }

    private void describeSprite(StringBuilder sb, Sprite sprite, boolean compact) {
        sb.append("### ").append(sprite.isStage() ? "舞台 " : "角色 ").append(sprite.name()).append('\n');
        sb.append("- 造型:").append(join(sprite.costumes())).append(";声音:").append(join(sprite.sounds()));
        if (!sprite.isStage()) {
            sb.append(";私有变量:").append(join(sprite.localVariables())).append(";私有列表:").append(join(sprite.localLists()));
        }
        sb.append('\n');
        List<Procedure> procedures = proceduresOf(sprite.name());
        if (!procedures.isEmpty()) {
            sb.append("- 自定义积木:").append(String.join("、", procedures.stream()
                    .map(p -> p.proccode().replace("%s", "()").replace("%b", "<>")).toList())).append('\n');
        }
        List<Script> own = scriptsOf(sprite.name());
        if (own.isEmpty()) {
            sb.append("- 脚本:无\n");
        } else if (!compact) {
            for (Script script : own) {
                sb.append("#### 脚本 #").append(script.id()).append("\n```\n").append(script.code().strip()).append("\n```\n");
            }
        }
    }

    private void describeGlobals(StringBuilder sb) {
        sb.append("## 全局\n- 变量:").append(join(globalVariables)).append(";列表:").append(join(globalLists))
                .append(";广播:").append(join(broadcasts)).append('\n');
    }

    private static String join(java.util.Collection<String> values) {
        return values.isEmpty() ? "无" : String.join("、", values);
    }
}
