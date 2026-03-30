# DepChain report (LaTeX)

This folder contains the Stage 1 + Stage 2 report in LNCS format.

## Build

From the repo root:

```bash
cd report
pdflatex depchain-stage2.tex
bibtex depchain-stage2
pdflatex depchain-stage2.tex
pdflatex depchain-stage2.tex
```

This assumes your LaTeX installation provides the LNCS class (`llncs.cls`) and bibliography style (`splncs04.bst`).

