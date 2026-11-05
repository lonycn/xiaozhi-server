# Server Components

The web frontend and backend share the `xiaozhi-server` Git repository and the `Dev` development branch.

| Directory | Responsibility |
| --- | --- |
| `manager-web/` | Vue 3, Vite, Pinia and Element Plus management UI |
| `manager-api/` | Java APIs, authorization and database migrations |
| `xiaozhi-server/` | Python voice services, provider adapters, device gateway and workers |

`manager-web-next` is a compatibility link to `manager-web`. The current source tree excludes the former Vue 2 frontend. Its source remains in local archives and Git history.

Run from the repository root:

```sh
npm ci --prefix main/manager-web --no-audit --no-fund
npm run dev --prefix main/manager-web
npm run build --prefix main/manager-web
```

The frontend runs at `http://127.0.0.1:18894` and proxies `/xiaozhi` to the local API. Public frontend settings use `import.meta.env.VITE_*`; local overrides belong in `.env.local`. Server credentials and customer callback configuration stay in ignored private files.

See the [frontend guide](manager-web/README.md), [Dev operations](../dev/README.md), and [documentation index](../docs/README.md). The [repository README](../README.md) is the primary project guide.
