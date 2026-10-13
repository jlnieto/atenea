import { expect, test } from "@playwright/test";

const changeKey = "59315b6e-59bc-4884-9def-356e1ca86ef4";
for (const width of [390, 1440]) {
  test(`change-only creation and completed history at width ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const posts: { path: string; key?: string }[] = [];
    await page.route("**/api/**", async route => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      const body = (value: unknown, status = 200) => route.fulfill({ status,
        contentType: "application/json", body: JSON.stringify(value) });
      if (request.method() === "POST") posts.push({ path, key: request.headers()["idempotency-key"] });
      if (path === "/api/web/auth/refresh") return body({ accessToken: "test-only", expiresInSeconds: 3600,
        operator: { id: 1, name: "Operador", email: "operator@example.test", codexOperationsRole: "PLATFORM_ADMINISTRATOR" } });
      if (path === "/api/mobile/projects/overview") return body([
        { projectId: 1, projectName: "Atenea", session: null }
      ]);
      if (path === "/api/v2/projects/1/development-changes" && request.method() === "GET") return body([
        { changeKey, projectId: 1, title: "Cambio real terminado", status: "COMPLETED", completedSessionId: 21 }
      ]);
      if (path === "/api/v2/projects/1/development-changes" && request.method() === "POST") return body({
        developmentChange: { changeKey, projectId: 1, status: "OPEN" }
      });
      if (path.endsWith("/workspace/provision")) return body({ state: "SUCCEEDED", workspaceState: "READY" });
      if (path.endsWith("/work-session:open-or-resolve")) return body({ changeKey, state: "SUCCEEDED", sessionState: "OPEN", sessionId: 22 });
      if (path.endsWith(changeKey)) return body({ version: 2, workspaceState: "READY" });
      if (path === "/api/mobile/sessions/22/conversation") return body({ view: {
        session: { id: 22, projectId: 1, title: "Nuevo trabajo", status: "OPEN" },
        runInProgress: false, canCreateTurn: true }, recentTurns: [], recentTurnLimit: 20, historyTruncated: false });
      return body({});
    });
    await page.goto("/#/projects", { waitUntil: "networkidle" });
    await expect(page.getByRole("button", { name: "Crear sesión", exact: true })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Rescate", exact: true })).toHaveCount(0);
    await page.getByRole("button", { name: "Ver cambios", exact: true }).click();
    await page.getByRole("button", { name: "Historial (1)", exact: true }).click();
    await expect(page.getByText("Cambio real terminado", { exact: true })).toBeVisible();
    expect(posts.filter(p => p.path !== "/api/web/auth/refresh")).toEqual([]);
    await page.getByRole("main").getByRole("button", { name: "Proyectos", exact: true }).click();
    await page.getByRole("textbox", { name: "Título del nuevo cambio" }).fill("Nuevo trabajo");
    await page.getByRole("button", { name: "Nuevo cambio", exact: true }).click();
    await expect(page).toHaveURL(/#\/conversation\/1\/22$/);
    const effects = posts.filter(p => p.path.includes("/development-changes"));
    expect(effects.map(p => p.path)).toEqual([
      "/api/v2/projects/1/development-changes",
      `/api/v2/projects/1/development-changes/${changeKey}/workspace/provision`,
      `/api/v2/projects/1/development-changes/${changeKey}/work-session:open-or-resolve`
    ]);
    expect(new Set(effects.map(p => p.key)).size).toBe(3);
    for (const effect of effects) expect(effect.key).toMatch(/^[0-9a-f-]{36}$/);
    expect(posts.some(p => p.path.includes("/sessions/resolve") || p.path.includes("/turns") || p.path.includes("rescue"))).toBe(false);
  });
}
