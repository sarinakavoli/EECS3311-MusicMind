# Daily Test Notes

What to run each day, and what you should see. Run everything from the project folder.

## Fri Oct 30: foundation (Tasks 1-5)

No new user-facing features today, so there is one check.

```bash
./gradlew build
```

**Expected:** `BUILD SUCCESSFUL`. The first run downloads some test files and can take a few minutes.

**Built today:** PostgreSQL storage, Spotify endpoints matching the Feb 2026 API, the OpenAI provider, central settings in `musicmind.properties`, the agent's tools, and LLM prompt and response checking.

**Not verified live:** nothing has run against real Spotify or OpenAI yet. Those calls were tested against local stand-in servers only.

**Before Saturday's test:** add `SPOTIFY_CLIENT_ID` and `LLM_API_KEY` to `.env`.
