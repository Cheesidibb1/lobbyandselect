package com.lobbyandselect.game;

import com.lobbyandselect.LobbyConfig;
import com.lobbyandselect.LobbyData;
import com.lobbyandselect.LobbyManager;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/** Geography trivia in lobby chat: first correct answer scores a point. */
public final class Trivia {
    private record Question(String text, List<String> answers) {}

    private static final List<Question> QUESTIONS = List.of(
            q("What is the capital of Australia?", "canberra"),
            q("What is the capital of Canada?", "ottawa"),
            q("What is the capital of Japan?", "tokyo"),
            q("What is the capital of Brazil?", "brasilia"),
            q("What is the capital of Egypt?", "cairo"),
            q("What is the capital of Kenya?", "nairobi"),
            q("What is the capital of Argentina?", "buenos aires"),
            q("What is the capital of Norway?", "oslo"),
            q("What is the capital of Turkey?", "ankara"),
            q("What is the capital of New Zealand?", "wellington"),
            q("What is the capital of South Korea?", "seoul"),
            q("What is the capital of Peru?", "lima"),
            q("What is the capital of Iceland?", "reykjavik"),
            q("What is the capital of Thailand?", "bangkok"),
            q("What is the capital of Morocco?", "rabat"),
            q("What is the tallest mountain on Earth?", "everest", "mount everest", "mt everest"),
            q("What is the longest river in Africa?", "nile", "the nile"),
            q("What is the largest ocean?", "pacific", "the pacific", "pacific ocean"),
            q("What is the largest desert that is not polar?", "sahara", "the sahara"),
            q("Which continent is Madagascar part of?", "africa"),
            q("Which country has the most people?", "india"),
            q("What is the largest country by area?", "russia"),
            q("What is the smallest country in the world?", "vatican", "vatican city"),
            q("In which country is the Great Barrier Reef?", "australia"),
            q("Which river flows through Paris?", "seine", "the seine"),
            q("Which river flows through London?", "thames", "the thames"),
            q("What is the largest lake in Africa?", "victoria", "lake victoria"),
            q("Which mountain range has Mount Everest?", "himalayas", "the himalayas", "himalaya"),
            q("What is the deepest point in the ocean called?", "challenger deep", "mariana trench", "the mariana trench"),
            q("Which country has the city of Machu Picchu?", "peru"),
            q("Which strait separates Europe and Africa?", "gibraltar", "strait of gibraltar"),
            q("What is the largest island in the world?", "greenland"),
            q("On which continent is the Atacama Desert?", "south america"),
            q("What is the capital of Germany?", "berlin"),
            q("Which ocean lies between Africa and Australia?", "indian", "indian ocean", "the indian ocean"),
            q("Which US state is the Grand Canyon in?", "arizona"),
            q("What country is Mount Fuji in?", "japan"),
            q("What is the longest river in South America?", "amazon", "the amazon"),
            q("Which country has the most islands?", "sweden"),
            q("What line of latitude is at 0 degrees?", "equator", "the equator")
    );

    @Nullable private static Question current;
    private static long ticks;
    private static long askedAt;
    private static long nextAt = 20 * 30;
    private static final List<Integer> ORDER = new ArrayList<>();

    private Trivia() {}

    private static Question q(String text, String... answers) {
        return new Question(text, List.of(answers));
    }

    public static void reset() {
        current = null;
        ticks = 0;
        nextAt = 20 * 30;
    }

    public static void tick(MinecraftServer server) {
        ticks++;
        if (!LobbyConfig.TRIVIA_ENABLED.get()) return;
        if (current != null) {
            if (ticks - askedAt > 20L * LobbyConfig.TRIVIA_ANSWER_SECONDS.get()) {
                broadcast(server, Component.literal("[Trivia] Time's up! The answer was: " + current.answers().get(0))
                        .withStyle(ChatFormatting.GRAY));
                current = null;
                nextAt = ticks + 20L * LobbyConfig.TRIVIA_INTERVAL_SECONDS.get();
            }
            return;
        }
        if (ticks < nextAt) return;
        if (LobbyManager.lobbyPlayers(server).isEmpty()) {
            nextAt = ticks + 20 * 10;
            return;
        }
        ask(server);
    }

    /** Asks a question now (also used by /lobby trivia). */
    public static void ask(MinecraftServer server) {
        if (ORDER.isEmpty()) {
            for (int i = 0; i < QUESTIONS.size(); i++) ORDER.add(i);
            Collections.shuffle(ORDER);
        }
        current = QUESTIONS.get(ORDER.remove(ORDER.size() - 1));
        askedAt = ticks;
        broadcast(server, Component.literal("[Trivia] ").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD)
                .append(Component.literal(current.text() + " (answer in chat)").withStyle(ChatFormatting.WHITE)));
    }

    public static void onChat(ServerPlayer player, String message) {
        Question question = current;
        if (question == null) return;
        String guess = normalize(message);
        for (String answer : question.answers()) {
            if (normalize(answer).equals(guess)) {
                current = null;
                nextAt = ticks + 20L * LobbyConfig.TRIVIA_INTERVAL_SECONDS.get();
                LobbyData data = LobbyData.get(player.server);
                LobbyData.PlayerRecord record = data.player(player.getUUID());
                record.triviaPoints++;
                data.setDirty();
                broadcast(player.server, Component.literal("[Trivia] " + player.getGameProfile().getName() + " got it: "
                        + question.answers().get(0) + "! (" + record.triviaPoints + " points)").withStyle(ChatFormatting.GREEN));
                player.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1f, 1f);
                return;
            }
        }
    }

    private static String normalize(String s) {
        String stripped = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return stripped.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", " ").trim();
    }

    private static void broadcast(MinecraftServer server, Component message) {
        for (ServerPlayer p : LobbyManager.lobbyPlayers(server)) p.sendSystemMessage(message);
    }
}
