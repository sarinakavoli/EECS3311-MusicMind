package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.UserPreference;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads the stored {@link UserPreference} records (F8) the way F4 uses them: blocked artists,
 * the explicit-content policy and the familiar-vs-exploratory bias. Also builds a preference
 * from the text a user types, so the CLI and GUI share one parser and one set of messages.
 *
 * <p>Every preference is one row keyed by name. Each key uses the column that suits it:
 * {@code value} for the lists, {@code explicitAllowed} for the policy, {@code familiarBias} for the bias.
 */
public final class PreferenceSettings {

    public static final String BLOCKED_ARTISTS = "blockedArtists";
    public static final String EXPLICIT_POLICY = "explicitPolicy";
    public static final String FAMILIAR_BIAS = "familiarBias";
    public static final String PREFERRED_STYLES = "preferredStyles";

    private static final String ARTIST_URI_PREFIX = "spotify:artist:";
    private static final String ALLOW = "allow";
    private static final String BLOCK = "block";

    private final Set<String> blockedArtists;
    private final Boolean explicitAllowed;
    private final Double familiarBias;
    private final Set<String> preferredStyles;

    private PreferenceSettings(Set<String> blockedArtists, Boolean explicitAllowed, Double familiarBias,
                               Set<String> preferredStyles) {
        this.blockedArtists = blockedArtists;
        this.explicitAllowed = explicitAllowed;
        this.familiarBias = familiarBias;
        this.preferredStyles = preferredStyles;
    }

    /** The settings held by these records; anything not stored stays unset. */
    public static PreferenceSettings from(List<UserPreference> preferences) {
        Set<String> blocked = new TreeSet<>();
        Set<String> styles = new TreeSet<>();
        Boolean explicit = null;
        Double bias = null;
        for (UserPreference p : preferences) {
            switch (p.key()) {
                case BLOCKED_ARTISTS -> blocked.addAll(split(p.value()));
                case PREFERRED_STYLES -> split(p.value()).forEach(style -> styles.add(style.toLowerCase(Locale.ROOT)));
                case EXPLICIT_POLICY -> explicit = p.explicitAllowed();
                case FAMILIAR_BIAS -> bias = p.familiarBias();
                case UserPreference.DEFAULTS_KEY -> {
                    // Safe defaults used while the database is down: no explicit content, a balanced leaning.
                    explicit = p.explicitAllowed();
                    bias = p.familiarBias();
                }
                default -> { }
            }
        }
        return new PreferenceSettings(Set.copyOf(blocked), explicit, bias, styles);
    }

    public Set<String> blockedArtists() {
        return blockedArtists;
    }

    /** Styles (genres) the user likes, lower case and alphabetical; they steer playlists (F5). */
    public Set<String> preferredStyles() {
        return preferredStyles;
    }

    /** Empty when the user never set a policy. */
    public Optional<Boolean> explicitAllowed() {
        return Optional.ofNullable(explicitAllowed);
    }

    /** Share of familiar tracks the user prefers by default (1.0 = all familiar); empty when never set. */
    public Optional<Double> familiarBias() {
        return Optional.ofNullable(familiarBias);
    }

    /**
     * A copy of the spec with the stored preferences applied: their blocked artists are added to the
     * spec's, and explicit content is allowed only if both the spec and the preference allow it.
     */
    public PlaylistSpec applyTo(PlaylistSpec spec) {
        Set<String> blocked = new LinkedHashSet<>(spec.blockedArtists());
        blocked.addAll(blockedArtists);
        boolean explicit = spec.allowExplicit() && explicitAllowed().orElse(true);
        return new PlaylistSpec(spec.targetMinutes(), explicit, spec.familiarRatio(),
                new ArrayList<>(blocked), spec.name());
    }

    /**
     * Builds the preference for a key and the text a user typed. The config supplies the neutral
     * value stored in the columns a key does not use.
     *
     * @throws IllegalArgumentException with a message fit to show the user for an unknown key or unusable value
     */
    public static UserPreference parse(String key, String value, Config config) {
        if (key == null) {
            throw unknownKey(null);
        }
        double neutralBias = config.discoverDefaultFamiliarRatio();
        String text = value == null ? "" : value.strip();
        return switch (key.strip()) {
            case BLOCKED_ARTISTS -> new UserPreference(BLOCKED_ARTISTS, join(split(text)), false, neutralBias);
            case PREFERRED_STYLES -> new UserPreference(PREFERRED_STYLES, join(split(text)), false, neutralBias);
            case EXPLICIT_POLICY -> {
                String policy = text.toLowerCase(Locale.ROOT);
                if (!policy.equals(ALLOW) && !policy.equals(BLOCK)) {
                    throw new IllegalArgumentException(EXPLICIT_POLICY + " must be '" + ALLOW + "' or '" + BLOCK + "'.");
                }
                yield new UserPreference(EXPLICIT_POLICY, policy, policy.equals(ALLOW), neutralBias);
            }
            case FAMILIAR_BIAS -> {
                try {
                    yield new UserPreference(FAMILIAR_BIAS, text, false, Double.parseDouble(text));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(FAMILIAR_BIAS + " must be a number between 0 and 1.");
                }
            }
            default -> throw unknownKey(key);
        };
    }

    /** The value a user should see for a stored preference. */
    public static String display(UserPreference p) {
        return switch (p.key()) {
            case EXPLICIT_POLICY -> p.explicitAllowed() ? ALLOW : BLOCK;
            case FAMILIAR_BIAS -> String.valueOf(p.familiarBias());
            case UserPreference.DEFAULTS_KEY -> "safe defaults (database unavailable): explicit "
                    + (p.explicitAllowed() ? ALLOW : BLOCK) + ", familiar bias " + p.familiarBias();
            default -> p.value();
        };
    }

    private static IllegalArgumentException unknownKey(String key) {
        return new IllegalArgumentException("Unknown preference '" + key + "'. Use one of: " + BLOCKED_ARTISTS + ", "
                + EXPLICIT_POLICY + ", " + FAMILIAR_BIAS + ", " + PREFERRED_STYLES + ".");
    }

    private static Set<String> split(String text) {
        Set<String> out = new TreeSet<>();
        if (text == null) {
            return out;
        }
        for (String part : text.split(",")) {
            String item = part.strip();
            if (item.startsWith(ARTIST_URI_PREFIX)) {
                item = item.substring(ARTIST_URI_PREFIX.length());
            }
            if (!item.isEmpty()) {
                out.add(item);
            }
        }
        return out;
    }

    private static String join(Set<String> items) {
        return String.join(",", items);
    }
}
