package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): intel only — prestige streak plus event
// quiz answers. It never sends chat and never clicks blocks.
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.Render2DEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.TextProperty;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.util.EnumChatFormatting;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ThePitUtils extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty display = new ModeProperty("display", 2, new String[]{"CHAT", "HUD", "BOTH"});
    public final BooleanProperty streak = new BooleanProperty("streak", true);
    public final BooleanProperty quiz = new BooleanProperty("quiz-alert", true);
    public final TextProperty quizWord = new TextProperty("quiz-word", "solve", () -> display.getValue() == 1);
    public final BooleanProperty announceQuiz = new BooleanProperty("announce-quiz", true, () -> display.getValue() == 2);
    public final FloatProperty scale = new FloatProperty("scale", 1.0F, 0.5F, 2.0F);
    public final IntProperty offsetX = new IntProperty("offset-x", 4, 0, 1000);
    public final IntProperty offsetY = new IntProperty("offset-y", 140, 0, 1000);

    private int kills;
    private int best;
    private String lastQuiz = "";
    private String lastAnswer = "";

    public ThePitUtils() {
        super("ThePitUtils", false, false, "Tracks The Pit streaks and shows quiz answers.");
    }

    @Override
    public void onEnabled() {
        reset();
    }

    @Override
    public void onDisabled() {
        reset();
    }

    @Override
    public String[] getSuffix() {
        return kills > 0 ? new String[]{kills + " ks"} : new String[0];
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || event.getType() != EventType.RECEIVE || !(event.getPacket() instanceof S02PacketChat)) {
            return;
        }
        String raw;
        try {
            raw = ((S02PacketChat) event.getPacket()).getChatComponent().getUnformattedText();
        } catch (Exception ignored) {
            return;
        }
        if (raw == null || raw.isEmpty()) {
            return;
        }
        String stripped;
        try {
            stripped = EnumChatFormatting.getTextWithoutFormattingCodes(raw);
        } catch (Exception ignored) {
            return;
        }
        if (stripped == null || stripped.isEmpty()) {
            return;
        }
        String lower = stripped.toLowerCase(Locale.ROOT);
        if (streak.getValue() && isKill(lower)) {
            kills++;
            best = Math.max(best, kills);
            if (display.getValue() == 0 || display.getValue() == 2) {
                ChatUtil.sendFormatted(String.format("%s%s: &fStreak &e%d&r &7(best &e%d&r)&r",
                        Myau.clientName, getName(), kills, best));
            }
            return;
        }
        if (streak.getValue() && (lower.contains("you died") || lower.contains("you were killed") || lower.contains("death"))) {
            kills = 0;
            return;
        }
        if (quiz.getValue() && isQuiz(lower)) {
            lastQuiz = stripped.trim();
            lastAnswer = solveQuiz(stripped);
            boolean chat = display.getValue() == 0 || (display.getValue() == 2 && announceQuiz.getValue());
            if (chat) {
                String hint = lastAnswer.isEmpty()
                        ? "&7(intel only, type it yourself)&r"
                        : "&7answer &e" + lastAnswer + " &7(intel only, type it yourself)&r";
                ChatUtil.sendFormatted(String.format("%s%s: &eQuiz: &r%s %s", Myau.clientName, getName(), stripped.trim(), hint));
            }
        }
    }

    @EventTarget
    public void onRender(Render2DEvent event) {
        if (!isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (display.getValue() == 0 || mc.gameSettings.showDebugInfo) {
            return;
        }
        List<String> lines = new ArrayList<String>();
        if (streak.getValue() && kills > 0) {
            lines.add("Streak: " + kills + " (best " + best + ")");
        }
        if (quiz.getValue() && !lastAnswer.isEmpty()) {
            lines.add("Quiz: " + lastAnswer);
        }
        if (lines.isEmpty()) {
            return;
        }
        float scaleValue = scale.getValue();
        ScaledResolution resolution = new ScaledResolution(mc);
        float baseX = (float) offsetX.getValue();
        float baseY = (float) offsetY.getValue();
        if (baseX > resolution.getScaledWidth() || baseY > resolution.getScaledHeight()) {
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(baseX, baseY, 0.0F);
        GlStateManager.scale(scaleValue, scaleValue, 1.0F);
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, mc.fontRendererObj.getStringWidth(line));
        }
        int height = lines.size() * (mc.fontRendererObj.FONT_HEIGHT + 1);
        Gui.drawRect(-2, -2, width + 2, height, new Color(0, 0, 0, 120).getRGB());
        float y = 0.0F;
        for (String line : lines) {
            mc.fontRendererObj.drawStringWithShadow(line, 0.0F, y, 16777215);
            y += mc.fontRendererObj.FONT_HEIGHT + 1.0F;
        }
        GlStateManager.popMatrix();
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        reset();
    }

    private boolean isKill(String lower) {
        return lower.contains("you killed") || lower.contains("kill! (")
                || lower.contains("+1 kill") || lower.contains("killstreak");
    }

    private boolean isQuiz(String lower) {
        String word = quizWord.getValue();
        if (word != null && !word.trim().isEmpty() && lower.contains(word.trim().toLowerCase(Locale.ROOT))) {
            return true;
        }
        return lower.contains("quiz") || lower.contains("trivia") || lower.contains("flash")
                || lower.contains("answer") || lower.contains("solve:");
    }

    private String solveQuiz(String stripped) {
        try {
            int idx = Math.max(stripped.lastIndexOf(':'),
                    Math.max(stripped.lastIndexOf('?'), stripped.lastIndexOf('=')));
            String expr = idx >= 0 ? stripped.substring(idx + 1).trim() : stripped.trim();
            expr = expr.replaceAll("[^0-9+\\-*/(). xX]", " ").trim();
            expr = expr.replaceAll("[xX]", "*");
            if (expr.isEmpty() || !expr.matches(".*\\d.*") || !expr.matches("[0-9+\\-*/(). ]+")) {
                return "";
            }
            double result = new Expr(expr).parse();
            if (Double.isNaN(result) || Double.isInfinite(result)) {
                return "";
            }
            return result == (long) result ? String.valueOf((long) result) : String.valueOf(result);
        } catch (Exception ignored) {
            return "";
        }
    }

    private void reset() {
        kills = 0;
        best = 0;
        lastQuiz = "";
        lastAnswer = "";
    }

    private static final class Expr {
        private final String text;
        private int pos;

        Expr(String text) {
            this.text = text;
        }

        double parse() {
            double value = expr();
            skip();
            if (pos < text.length()) {
                throw new IllegalArgumentException("trailing");
            }
            return value;
        }

        private double expr() {
            double value = term();
            while (true) {
                skip();
                if (eat('+')) {
                    value += term();
                } else if (eat('-')) {
                    value -= term();
                } else {
                    return value;
                }
            }
        }

        private double term() {
            double value = factor();
            while (true) {
                skip();
                if (eat('*')) {
                    value *= factor();
                } else if (eat('/')) {
                    value /= factor();
                } else {
                    return value;
                }
            }
        }

        private double factor() {
            skip();
            if (eat('+')) {
                return factor();
            }
            if (eat('-')) {
                return -factor();
            }
            if (eat('(')) {
                double value = expr();
                eat(')');
                return value;
            }
            int start = pos;
            while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("number");
            }
            return Double.parseDouble(text.substring(start, pos));
        }

        private void skip() {
            while (pos < text.length() && text.charAt(pos) == ' ') {
                pos++;
            }
        }

        private boolean eat(char ch) {
            skip();
            if (pos < text.length() && text.charAt(pos) == ch) {
                pos++;
                return true;
            }
            return false;
        }
    }
}
