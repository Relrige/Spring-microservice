---
name: autopilot
description: Autopilot mode for VoltStore backend work — implement a sequence of task files end to end within one service. Clarify the spec and tasks first, then implement, test, document decisions, commit per task, sync the spec, push, and open a PR. Never merges. Started only by the user (/autopilot or by naming the mode).
disable-model-invocation: true
---

# Autopilot Mode

You implement a sequence of tasks from `docs/tasks/<service>/` by yourself: code, tests, decision notes, commits, a spec sync, and a pull request. The user reviews the result as a PR.

The mode stays active for the rest of the session until the user switches to another one (for example with `/mentor`). Everything in the root `AGENTS.md` still applies.

Autonomy here is bounded on purpose. The team is learning architecture (root `AGENTS.md` §2), so architectural choices stay with them; you take care of the details and leave a written trail that lets them understand and challenge every choice you made. Three things make that work: clarifying before you start, staying inside one service, and documenting decisions.

Designing is not autopilot work. If the request is to design something (a new spec, a new service, a task backlog), say so and suggest `/design`, unless the user explicitly asks you to write the backlog in this mode.

## 1. Clarify before starting

This is the most important step. A wrong assumption early in a task sequence is expensive to undo, and the user expects to be asked rather than surprised.

1. Agree on the scope: which tasks (for example "Order tasks 18–20"), in which service.
2. Read everything the tasks depend on:
   - the task files (all of them, not just the first);
   - the spec sections they reference in `docs/design/services-specs/`, plus `api-contracts.md`, `events-catalogue.md`, `security-model.md`, and `entities.md` where relevant;
   - notes and *Deviations from spec / task* sections in earlier task files of the same service (they may override the spec, see root `AGENTS.md` §4.2);
   - the existing code the tasks touch.
3. Collect every ambiguity, gap, or contradiction you find (task vs. spec, spec vs. code, spec vs. another service's contract, unclear acceptance criteria) and ask about **all of them in one message**. Number the questions and give your proposed answer for each, so the user can reply briefly.
4. Wait for the answers. Do not start implementing while any question is open.
5. Record the answers in the task files they belong to (in a *Decisions* section), so they survive the session.

If you find nothing to ask, say so explicitly and list the assumptions you will work with.

## 2. Branch

- **On `main`:** update it (`git pull --ff-only`), then create a branch `<service>/<short-topic>`, for example `order-service/review-fixes`.
- **On any other branch:** ask whether to continue on it or create a new branch from up-to-date `main`.
- **Uncommitted changes in the working tree:** ask before doing anything with them.

Never commit to `main` and never push to it.

## 3. Stay inside one service

Work within the scope of the service being implemented:
- the service's folder;
- its own parts of shared files and folders: its entries in `docker-compose.yml`, `k8s/<service>/`, its line in `.github/workflows/ci.yml`, its task files, and its spec.

Changes to another service (including the API Gateway's route table) are out of scope unless the user explicitly asked for cross-cutting work. If a task turns out to need one, stop and ask. One service per branch keeps PRs reviewable and avoids colliding with work the other developer is doing in that service.

## 4. Decide details, ask about architecture

While implementing, undiscussed questions will come up.

**Decide yourself** when the choice stays inside the service and does not change how the system fits together. For example: class and package structure, validation details, error message texts, internal method signatures, test structure, configuration defaults, an index or a column type, a log level.

**Stop and ask** when the choice changes the architecture or something other people depend on:
- a REST contract or an event schema other services use (new, changed, or removed fields, endpoints, or events);
- synchronous vs. asynchronous communication, or which service owns which data;
- the saga flow, order states, or consistency guarantees;
- the security model;
- a new library, framework, infrastructure component, or pattern not already in use (root `AGENTS.md` §5.3);
- dropping or substantially re-scoping an acceptance criterion.

When unsure which side a question falls on, ask.

## 5. Implement each task

For each task, in order:

1. Set `status: IN_PROGRESS` in the task's front matter.
2. Implement it, following the code style and patterns already in the service.
3. Test it (see §6). Do not commit failing code; if you cannot make the tests pass, stop and report.
4. Document it in the task file:
   - **`## Implementation Notes (YYYY-MM-DD)`**: what was built and how, with a **Verified** bullet listing the tests run and what they cover.
   - **`## Deviations from spec / task`**: every place where the implementation differs from the spec or the task, including the details you decided yourself. For each: what the spec said, what you did, the alternatives you considered, and why you chose this one. Write it so a teammate learns the trade-off from reading it; this is how the team keeps learning while you write the code.
5. Tick the acceptance criteria and set `status: DONE`.
6. Commit the task on its own: subject `<Service> task NN: <summary>`, for example `Order task 18: refund on cancellation after payment`.

## 6. Testing

- Run Maven through the service's wrapper, from the service folder: `.\mvnw.cmd test` (PowerShell). Integration tests use Testcontainers, so Docker must be running.
- During a task, run the tests that cover it. Before opening the PR, run the service's full test suite.
- Run Compose or Kubernetes (minikube) checks when a task's acceptance criteria require it or the change affects deployment. Follow the guides in `e2e/manual/`, and stop the environment you started when done.

## 7. Sync the spec

When all tasks in the sequence are done, make the spec match the implementation, so the spec stays true and up to date:

1. Go through the source code and the notes and deviations in this run's task files.
2. Update every affected file in `docs/design/`: the service spec, and `api-contracts.md`, `events-catalogue.md`, `entities.md`, `order-state-machine.md`, or `security-model.md` where they are affected.
3. Keep the deviation notes in the task files as history, and add a line to each one: `*Spec sync (YYYY-MM-DD):* now part of the <service> spec (<section>).`
4. Commit this separately, for example `Order spec: sync with tasks 18–20`.

## 8. Push and open a pull request

1. Push the branch: `git push -u origin <branch>`.
2. Open a PR against `main` with `gh pr create --base main`. The description contains:
   - **Tasks:** the tasks done, each linked to its task file.
   - **Decisions and deviations:** each decision you made by yourself, with its trade-off in one or two sentences, and links to the task file sections.
   - **Spec sync:** which design files changed.
   - **Verification:** the tests and environment checks run, and their results.
   - **Open points:** anything left unfinished, out of scope, or worth discussing.
3. Never merge the PR and never force-push a branch someone else may be using. Merging is the team's decision after review.

Finish with a short summary to the user: the PR link, the decisions worth reviewing, and anything left open.
