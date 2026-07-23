import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AppCatalogPage } from "./AppCatalogPage";
import { useAuthStore } from "../auth/auth.store";
import type { MobileApp, MobileUser } from "../../shared/api/types";

const APPS: MobileApp[] = Array.from({ length: 12 }, (_, i) => ({
  formId: i + 1,
  code: `app-${i + 1}`,
  name: i === 0 ? "请假申请" : i === 1 ? "报销审批" : i === 2 ? "加班申请" : `通用应用 ${i + 1}`,
  category: i % 3 === 0 ? "hr" : i % 3 === 1 ? "finance" : "",
  categoryLabel: i % 3 === 0 ? "人事" : i % 3 === 1 ? "财务" : "",
}));

const AUTH_USER: MobileUser = {
  id: 1,
  username: "admin",
  displayName: "管理员",
  roles: ["admin"],
};

function setupFetch(apps: MobileApp[]) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL) => {
      const url = typeof input === "string" ? input : input.toString();
      if (url.includes("/api/mobile/apps")) {
        const params = new URL(url, "http://localhost").searchParams;
        const category = params.get("category");
        const filtered = apps.filter((app) => {
          const appCategory = app.category || "other";
          const matchesCategory = category ? appCategory === category : true;
          return matchesCategory;
        });
        return new Response(JSON.stringify(filtered), {
          status: 200,
          headers: { "content-type": "application/json" },
        });
      }
      if (url.includes("/api/mobile/bootstrap")) {
        return new Response(
          JSON.stringify({
            user: AUTH_USER,
            pendingCount: 0,
            favoriteApps: apps.slice(0, 2),
            recentProcesses: [],
            brandingVersion: "test-1",
          }),
          { status: 200, headers: { "content-type": "application/json" } },
        );
      }
      return new Response(JSON.stringify({}), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }),
  );
}

function renderCatalog() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={["/apps"]}>
        <AppCatalogPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  useAuthStore.setState({ status: "authenticated", accessToken: "t", user: AUTH_USER });
  vi.unstubAllGlobals();
});

describe("AppCatalogPage", () => {
  it("renders category groups and app tiles", async () => {
    setupFetch(APPS);
    renderCatalog();

    expect(await screen.findByRole("heading", { name: "全部应用" })).toBeInTheDocument();
    expect(await screen.findAllByText("人事")).not.toHaveLength(0);
    expect(await screen.findByText("财务")).toBeInTheDocument();
    expect(await screen.findByText("请假申请")).toBeInTheDocument();
  });

  it("renders category tabs and filters by selected category", async () => {
    setupFetch(APPS);
    renderCatalog();

    expect(await screen.findByText("请假申请")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "财务" }));
    await waitFor(() => {
      expect(screen.getByText("报销审批")).toBeInTheDocument();
      expect(screen.queryByText("请假申请")).not.toBeInTheDocument();
    });
  });

  it("maps uncategorized apps to the other category tab", async () => {
    setupFetch(APPS);
    renderCatalog();

    expect(await screen.findByText("请假申请")).toBeInTheDocument();
    fireEvent.click(screen.getAllByRole("tab", { name: "其他" })[0]);
    await waitFor(() => {
      expect(screen.getByText("通用应用 7")).toBeInTheDocument();
      expect(screen.queryByText("请假申请")).not.toBeInTheDocument();
    });
    const fetchMock = fetch as unknown as { mock: { calls: unknown[][] } };
    const calledOther = fetchMock.mock.calls.some((entry) =>
      String(entry[0]).includes("category=other"),
    );
    expect(calledOther).toBe(true);
  });
});
