import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { WorkbenchPage } from "./WorkbenchPage";
import type { MobileBootstrap, MobileApp, RecentProcess } from "../../shared/api/types";
import { MAX_FAVORITE_APPS, MAX_RECENT_PROCESSES } from "./workbench.api";

function makeApp(id: number, name = `App ${id}`): MobileApp {
  return { formId: id, code: `app-${id}`, name, category: "other", categoryLabel: "其他" };
}

function makeProcess(id: number, status: RecentProcess["status"] = "RUNNING"): RecentProcess {
  return {
    instanceId: id,
    formCode: `form-${id}`,
    formTitle: `流程 ${id}`,
    status,
    updatedAt: "2026-07-18T08:00:00Z",
  };
}

const SAMPLE_USER = {
  id: 1,
  username: "admin",
  displayName: "管理员",
  roles: ["admin"],
};

function wrapWithQuery(ui: React.ReactNode, fetchMock: ReturnType<typeof vi.fn>) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  vi.stubGlobal("fetch", fetchMock);
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={["/workbench"]}>{ui}</MemoryRouter>
    </QueryClientProvider>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("WorkbenchPage", () => {
  it("renders skeleton while the bootstrap query is pending", () => {
    const fetchMock = vi.fn().mockReturnValue(new Promise(() => {}));
    wrapWithQuery(<WorkbenchPage />, fetchMock);
    expect(screen.getByRole("status")).toBeInTheDocument();
  });

  it("caps favorite apps at the maximum and surfaces process list", async () => {
    const apps = Array.from({ length: 12 }, (_, i) => makeApp(i + 1));
    const processes = Array.from({ length: 5 }, (_, i) => makeProcess(i + 1));
    const payload: MobileBootstrap = {
      user: SAMPLE_USER,
      pendingCount: 4,
      favoriteApps: apps,
      recentProcesses: processes,
      brandingVersion: "tenant-2026-07-18",
    };
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(payload), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );
    wrapWithQuery(<WorkbenchPage />, fetchMock);

    await waitFor(() => {
      expect(screen.getByTestId("workbench")).toBeInTheDocument();
    });
    expect(screen.getByTestId("workbench").textContent ?? "").toContain("管理员");
    expect(screen.getByText("App 8")).toBeInTheDocument();
    expect(screen.queryByText("App 9")).not.toBeInTheDocument();
    expect(screen.getByText("流程 1")).toBeInTheDocument();
    expect(screen.getByText("流程 3")).toBeInTheDocument();
    expect(screen.queryByText("流程 4")).not.toBeInTheDocument();
  });

  it("reports error state with retry capability", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ code: "BOOTSTRAP_FAILED", message: "boom" }), {
        status: 500,
        headers: { "content-type": "application/json" },
      }),
    );
    wrapWithQuery(<WorkbenchPage />, fetchMock);

    await waitFor(() => {
      expect(screen.getByText("工作台加载失败")).toBeInTheDocument();
    });
    expect(screen.getByRole("alert")).toBeInTheDocument();
  });

  it("shows empty state when bootstrap returns no favourites or recent processes", async () => {
    const payload: MobileBootstrap = {
      user: SAMPLE_USER,
      pendingCount: 0,
      favoriteApps: [],
      recentProcesses: [],
      brandingVersion: "tenant-2026-07-18",
    };
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(payload), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );
    wrapWithQuery(<WorkbenchPage />, fetchMock);

    await waitFor(() => {
      expect(screen.getByText("还没有常用应用")).toBeInTheDocument();
    });
    expect(screen.getByText("还没有最近的流程")).toBeInTheDocument();
  });

  it("exposes the documented caps so the page can rely on them", () => {
    expect(MAX_FAVORITE_APPS).toBe(8);
    expect(MAX_RECENT_PROCESSES).toBe(3);
  });
});
