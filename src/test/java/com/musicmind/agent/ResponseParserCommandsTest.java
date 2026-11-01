package com.musicmind.agent;

import com.musicmind.agent.command.AddItemsCommand;
import com.musicmind.agent.command.PlaylistCommand;
import com.musicmind.agent.command.RemoveTrackCommand;
import com.musicmind.agent.command.ReorderCommand;
import com.musicmind.agent.command.ReplaceTrackCommand;
import com.musicmind.config.Config;
import com.musicmind.domain.Track;
import com.musicmind.llm.ParseException;
import com.musicmind.music.MusicServiceException;
import com.musicmind.music.StubMusicService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseParserCommandsTest {

    private final Config config = Config.of(Map.of());
    private final ResponseParser parser = new ResponseParser(config);
    private ResponseParser bound;
    private final List<String> searches = new ArrayList<>();
    private final Map<String, List<Track>> results = Map.of(
            "mellow piano", List.of(t("a"), t("n1"), t("n2")),
            "upbeat", List.of(t("n3")));

    private final StubMusicService music = new StubMusicService() {
        @Override public List<Track> search(String query, int limit) {
            searches.add(query + "/" + limit);
            if (query.equals("down")) {
                throw new MusicServiceException("offline");
            }
            return results.getOrDefault(query, List.of());
        }
    };
    private final PlaylistContext context = new PlaylistContext("p", List.of(t("a"), t("b"), t("c")), music);

    @org.junit.jupiter.api.BeforeEach
    void bind() {
        bound = parser.forPlaylist(context);
    }

    @Test
    void anUnboundParserRefusesToParseCommands() {
        assertThrows(IllegalStateException.class, () -> parser.parseCommands("{\"commands\":[{\"type\":\"remove\",\"track\":\"1\"}]}"));
    }

    private static Track t(String id) {
        return new Track(id, "spotify:track:" + id, "T" + id, 1000, false, List.of());
    }

    @Test
    void bindsEveryCommandTypeToTheRealPlaylist() {
        List<PlaylistCommand> cmds = bound.parseCommands("{\"commands\":["
                + "{\"type\":\"remove\",\"track\":\"2\"},"
                + "{\"type\":\"add\",\"query\":\"mellow piano\"},"
                + "{\"type\":\"replace\",\"track\":\"c\",\"query\":\"upbeat\"},"
                + "{\"type\":\"reorder\",\"track\":\"spotify:track:a\",\"position\":2}]}");
        assertEquals(4, cmds.size());
        assertInstanceOf(RemoveTrackCommand.class, cmds.get(0));
        assertTrue(cmds.get(0).describe().contains("Tb"), "position 2 is track b");
        assertInstanceOf(AddItemsCommand.class, cmds.get(1));
        assertTrue(cmds.get(1).describe().contains("Tn1"), "a (already in the playlist) is skipped");
        assertInstanceOf(ReplaceTrackCommand.class, cmds.get(2));
        assertTrue(cmds.get(2).describe().contains("Tc"));
        assertTrue(cmds.get(2).describe().contains("Tn3"));
        assertInstanceOf(ReorderCommand.class, cmds.get(3));
        assertTrue(cmds.stream().allMatch(c -> "p".equals(c.playlistId())));
        assertTrue(searches.stream().allMatch(s -> s.endsWith("/" + config.commandSearchLimit())));
    }

    @Test
    void aTrackIsNeverChosenTwiceInOneEdit() {
        List<PlaylistCommand> cmds = bound.parseCommands("{\"commands\":["
                + "{\"type\":\"add\",\"query\":\"mellow piano\"},"
                + "{\"type\":\"add\",\"query\":\"mellow piano\"}]}");
        assertTrue(cmds.get(0).describe().contains("Tn1"));
        assertTrue(cmds.get(1).describe().contains("Tn2"));
    }

    @Test
    void unresolvableEditsAreRejected() {
        for (String bad : new String[]{
                "{\"commands\":[{\"type\":\"remove\",\"track\":\"9\"}]}",
                "{\"commands\":[{\"type\":\"remove\",\"track\":\"0\"}]}",
                "{\"commands\":[{\"type\":\"reorder\",\"track\":\"nope\",\"position\":1}]}",
                "{\"commands\":[{\"type\":\"add\",\"query\":\"no such song\"}]}",
                "{\"commands\":[{\"type\":\"replace\",\"track\":\"1\",\"query\":\"no such song\"}]}",
                "{\"commands\":[]}"}) {
            assertThrows(ParseException.class, () -> bound.parseCommands(bad), bad);
        }
    }

    @Test
    void aSearchFailureIsNotHiddenAsAParseProblem() {
        assertThrows(MusicServiceException.class, () -> bound.parseCommands(
                "{\"commands\":[{\"type\":\"add\",\"query\":\"down\"}]}"));
    }

    @Test
    void editExtrasReadTheMoodTheQuestionAndWhetherThereAreCommands() {
        ResponseParser.EditExtras none = parser.parseEditExtras("{\"commands\":[],\"mood\":\" Calm \",\"question\":\"\"}");
        assertEquals(false, none.hasCommands());
        assertEquals("calm", none.mood());
        assertEquals("", none.question());
        ResponseParser.EditExtras some = parser.parseEditExtras(
                "{\"commands\":[{\"type\":\"remove\",\"track\":\"1\"}],\"question\":\"Which?\"}");
        assertTrue(some.hasCommands());
        assertEquals("", some.mood());
        assertEquals("Which?", some.question());
        assertEquals(false, parser.parseEditExtras("{}").hasCommands());
        assertThrows(ParseException.class, () -> parser.parseEditExtras("nope"));
    }

    @Test
    void editExtrasReadAStatedLengthChangeOrAbsoluteLengthAndIgnoreWhatIsNotUsable() {
        assertEquals(-15, parser.parseEditExtras("{\"lengthChangeMinutes\":-15}").lengthChangeMinutes());
        assertEquals(10, parser.parseEditExtras("{\"lengthChangeMinutes\":10.4}").lengthChangeMinutes());
        assertEquals(60, parser.parseEditExtras("{\"targetMinutes\":60}").targetMinutes());
        ResponseParser.EditExtras none = parser.parseEditExtras("{\"commands\":[]}");
        assertEquals(0, none.lengthChangeMinutes());
        assertEquals(0, none.targetMinutes());
        for (String bad : List.of("{\"lengthChangeMinutes\":\"lots\"}", "{\"lengthChangeMinutes\":null}",
                "{\"lengthChangeMinutes\":99999}", "{\"targetMinutes\":-5}", "{\"targetMinutes\":99999}")) {
            ResponseParser.EditExtras e = parser.parseEditExtras(bad);
            assertEquals(0, e.lengthChangeMinutes(), bad);
            assertEquals(0, e.targetMinutes(), bad);
        }
    }
}
