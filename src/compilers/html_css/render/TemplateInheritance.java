package compilers.html_css.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves {@code {% extends %}} chains (plan section 6.2 item 1).
 *
 * <p>The chain is ordered most-derived first: {@code [child, parent, …,
 * base]}. Rendering starts from the base template's tree; every
 * {@code {% block name %}} looks the name up from the most-derived template
 * downwards, so child blocks override parents while unoverridden parent
 * blocks keep their default content.</p>
 */
public final class TemplateInheritance {

    /** Loads (and caches) a parsed model by template name. */
    public interface ModelLoader {
        JinjaTemplateModel load(String templateName);
    }

    private static final int MAX_CHAIN = 10;

    private TemplateInheritance() {
    }

    /** @return the inheritance chain, most-derived template first. */
    public static List<JinjaTemplateModel> resolve(
            JinjaTemplateModel child, ModelLoader loader) {
        List<JinjaTemplateModel> chain = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        JinjaTemplateModel current = child;
        while (true) {
            if (seen.contains(current.getTemplateName())) {
                throw new JinjaTemplateModel.TemplateStructureException(
                        "Circular {% extends %} chain: " + seen, 0, 0);
            }
            seen.add(current.getTemplateName());
            chain.add(current);
            if (current.getExtendsName() == null) {
                return chain;
            }
            if (chain.size() >= MAX_CHAIN) {
                throw new JinjaTemplateModel.TemplateStructureException(
                        "{% extends %} chain deeper than " + MAX_CHAIN, 0, 0);
            }
            current = loader.load(current.getExtendsName());
        }
    }

    /** The tree rendering starts from: the base-most template's root. */
    public static JinjaTemplateModel baseTemplate(List<JinjaTemplateModel> chain) {
        return chain.get(chain.size() - 1);
    }

    /**
     * Block override lookup: the most-derived definition of {@code name},
     * or {@code null} when no template in the chain defines it.
     */
    public static JinjaTemplateModel.BlockDef lookupBlock(
            List<JinjaTemplateModel> chain, String name) {
        for (JinjaTemplateModel model : chain) {
            JinjaTemplateModel.BlockDef block = model.getBlocks().get(name);
            if (block != null) {
                return block;
            }
        }
        return null;
    }
}
