# Creating the GitHub repository (one-time)

The assignment requires **one public GitHub repository**, used across all three
stages. Do this on your own computer (so the commits are authored by you).

## 1. Put these files in a folder
Unzip the delivered `MusicMind` folder somewhere, e.g. `~/EECS3311/MusicMind`.

## 2. Set your git identity (if you haven't already)
```bash
git config --global user.name  "Your Name"
git config --global user.email "you@example.com"
```

## 3. Initialize the repo and make the first commit
```bash
cd ~/EECS3311/MusicMind
git init
git add .
git commit -m "Stage 1: design report, UMLet diagrams, project scaffold"
```

## 4. Create the GitHub repo and push

**Option A — GitHub website (simplest):**
1. Go to https://github.com/new
2. Name it e.g. `musicmind-eecs3311`, set it to **Public**, do **not** add a README
   (you already have one), then **Create repository**.
3. Follow the "push an existing repository" commands GitHub shows:
```bash
git remote add origin https://github.com/<your-username>/musicmind-eecs3311.git
git branch -M main
git push -u origin main
```

**Option B — GitHub CLI (`gh`):**
```bash
gh auth login            # once, if not already logged in
gh repo create musicmind-eecs3311 --public --source=. --remote=origin --push
```

## 5. Verify and submit
- Open the repo URL in a browser — confirm it is **Public** and shows your files.
- Submit **that URL** through the course submission system.

## Reminders
- **Never commit real credentials.** `.env` is git-ignored; only `.env.example` is tracked.
- Keep using **this same repository** for Stage 2 and Stage 3 — don't create a new one.
- Export your UMLet diagrams to `docs/diagrams/png/` before the deadline if you want
  the images visible directly on GitHub.
