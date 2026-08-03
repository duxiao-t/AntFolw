// AntFlow 预览 · 共享交互(纯演示用)
(function () {
  // Tab Bar 在所有带底部 tabbar 的页面都生效
  document.querySelectorAll(".tabbar").forEach((bar) => {
    bar.addEventListener("click", (e) => {
      const btn = e.target.closest(".tabbar__item");
      if (!btn) return;
      bar.querySelectorAll(".tabbar__item").forEach((b) => b.classList.remove("is-active"));
      btn.classList.add("is-active");
    });
  });

  // 任务中心的 view tabs(pending/process/done)
  document.querySelectorAll(".tabs").forEach((tabs) => {
    tabs.addEventListener("click", (e) => {
      const btn = e.target.closest(".tab");
      if (!btn) return;
      tabs.querySelectorAll(".tab").forEach((b) => b.classList.remove("is-active"));
      btn.classList.add("is-active");
    });
  });

  // Chip 行默认支持多选；标记 data-chip-select="single" 时改为单选。
  document.querySelectorAll(".chip-row").forEach((row) => {
    row.addEventListener("click", (e) => {
      const chip = e.target.closest(".chip");
      if (!chip) return;
      if (row.dataset.chipSelect === "single") {
        row.querySelectorAll(".chip").forEach((item) => item.classList.remove("is-active"));
        chip.classList.add("is-active");
        return;
      }
      chip.classList.toggle("is-active");
    });
  });

  // Self-select 选人
  document.querySelectorAll(".people-grid").forEach((grid) => {
    grid.addEventListener("click", (e) => {
      const p = e.target.closest(".person");
      if (!p) return;
      p.classList.toggle("is-active");
    });
  });

  // Sheet 显隐:点击 [data-open-sheet] 打开对应 id 的 sheet
  document.querySelectorAll("[data-open-sheet]").forEach((trigger) => {
    trigger.addEventListener("click", () => {
      const id = trigger.getAttribute("data-open-sheet");
      const sheet = document.getElementById(id);
      const mask = document.getElementById(id + "-mask");
      if (sheet) sheet.classList.add("is-open");
      if (mask) mask.classList.add("is-open");
    });
  });
  // 关闭按钮 / 蒙层点击 关闭
  document.querySelectorAll("[data-close-sheet], .sheet-mask").forEach((el) => {
    el.addEventListener("click", () => {
      el.closest("[data-sheet-root]")?.querySelectorAll(".sheet.is-open, .sheet-mask.is-open").forEach((n) => n.classList.remove("is-open"));
      document.querySelectorAll(".sheet.is-open, .sheet-mask.is-open").forEach((n) => n.classList.remove("is-open"));
    });
  });

  // 暗色主题切换
  const themeBtn = document.querySelector(".theme-toggle");
  if (themeBtn) {
    themeBtn.addEventListener("click", () => {
      const cur = document.documentElement.getAttribute("data-theme");
      const next = cur === "dark" ? "" : "dark";
      if (next) document.documentElement.setAttribute("data-theme", next);
      else document.documentElement.removeAttribute("data-theme");
    });
  }
})();
