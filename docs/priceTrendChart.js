import React from "react";
import { createRoot } from "react-dom/client";
import {
  ResponsiveContainer,
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend
} from "recharts";

const h = React.createElement;

const SERIES_COLORS = [
  "#10b981", // Emerald
  "#22d3ee", // Cyan
  "#f59e0b", // Amber
  "#a78bfa", // Violet
  "#fb7185", // Rose
  "#38bdf8"  // Sky
];

let reactRoot = null;

function CustomTrendTooltip({ active, payload, label, currency, formatMoney }) {
  if (!active || !Array.isArray(payload) || payload.length === 0) {
    return null;
  }

  const rowMeta = payload[0]?.payload || {};
  const validEntries = payload
    .filter((entry) => typeof entry.value === "number" && !Number.isNaN(entry.value))
    .slice()
    .sort((a, b) => a.value - b.value);

  const spread = validEntries.length >= 2
    ? validEntries[validEntries.length - 1].value - validEntries[0].value
    : null;

  return h(
    "div",
    { className: "trend-tooltip" },
    h("div", { className: "trend-tooltip-title" }, label || rowMeta.label || "Snapshot"),
    rowMeta.kindLabel
      ? h("div", { className: "trend-tooltip-kind" }, rowMeta.kindLabel)
      : null,
    validEntries.map((entry) =>
      h(
        "div",
        { key: entry.dataKey, className: "trend-tooltip-row" },
        h(
          "span",
          { className: "trend-tooltip-name" },
          h("span", {
            className: "trend-tooltip-swatch",
            style: { backgroundColor: entry.color || "#10b981" }
          }),
          entry.name
        ),
        h("span", { className: "trend-tooltip-val" }, formatMoney(entry.value, currency))
      )
    ),
    spread != null
      ? h(
          "div",
          { className: "trend-tooltip-spread" },
          `Cross-platform variance: ${formatMoney(spread, currency)}`
        )
      : null
  );
}

function PriceTrendChartView({
  query,
  currency,
  series,
  platforms,
  minPrice,
  maxPrice,
  variance,
  observationCount,
  provenanceNote,
  onClearHistory,
  formatMoney
}) {
  if (!series || series.length === 0 || !platforms || platforms.length === 0) {
    return h(
      "div",
      { className: "empty-state", style: { padding: "22px 16px" } },
      h("p", { style: { margin: 0 } }, `No historical or multi-platform price points available yet for "${query}".`)
    );
  }

  return h(
    React.Fragment,
    null,
    h(
      "div",
      { className: "trend-header" },
      h(
        "div",
        null,
        h("h2", { className: "trend-title" }, `Historical Price Trend & Platform Variance`),
        h(
          "p",
          { className: "trend-subtitle" },
          `Comparing "${query}" across ${platforms.length} platform${platforms.length === 1 ? "" : "s"} in ${currency} (${observationCount} time point${observationCount === 1 ? "" : "s"})`
        )
      ),
      h(
        "div",
        { className: "trend-header-actions" },
        h(
          "button",
          {
            type: "button",
            className: "btn btn-secondary btn-xs",
            onClick: onClearHistory
          },
          "Clear Saved History"
        )
      )
    ),
    h(
      "div",
      { className: "trend-stats-row" },
      minPrice != null
        ? h("span", { className: "badge badge--emerald" }, `Floor: ${formatMoney(minPrice, currency)}`)
        : null,
      maxPrice != null
        ? h("span", { className: "badge badge--cyan" }, `Peak: ${formatMoney(maxPrice, currency)}`)
        : null,
      variance != null
        ? h("span", { className: "badge badge--amber" }, `Platform Variance: ${formatMoney(variance, currency)}`)
        : null
    ),
    h(
      "div",
      { className: "trend-chart-wrap" },
      h(
        ResponsiveContainer,
        { width: "100%", height: 290 },
        h(
          LineChart,
          {
            data: series,
            margin: { top: 12, right: 20, left: 6, bottom: 8 }
          },
          h("CartesianGrid", {
            strokeDasharray: "3 3",
            stroke: "rgba(148, 197, 214, 0.14)"
          }),
          h("XAxis", {
            dataKey: "label",
            stroke: "#9eb9c7",
            tick: { fill: "#9eb9c7", fontSize: 12 }
          }),
          h("YAxis", {
            stroke: "#9eb9c7",
            tick: { fill: "#9eb9c7", fontSize: 12 },
            tickFormatter: (val) => formatMoney(val, currency),
            width: 82,
            domain: ["auto", "auto"]
          }),
          h(Tooltip, {
            content: (props) =>
              h(CustomTrendTooltip, {
                ...props,
                currency,
                formatMoney
              })
          }),
          h(Legend, {
            wrapperStyle: { fontSize: "12px", paddingTop: "8px", color: "#9eb9c7" }
          }),
          platforms.map((platformName, idx) =>
            h(Line, {
              key: platformName,
              type: "monotone",
              dataKey: platformName,
              name: platformName,
              stroke: SERIES_COLORS[idx % SERIES_COLORS.length],
              strokeWidth: 2.5,
              dot: { r: 4.5, strokeWidth: 2, fill: "#0f232e" },
              activeDot: { r: 7 },
              connectNulls: true,
              isAnimationActive: false
            })
          )
        )
      )
    ),
    h("div", { className: "trend-footnote" }, provenanceNote)
  );
}

export function renderRechartsTrend(container, props) {
  if (!container) return;
  if (!reactRoot) {
    reactRoot = createRoot(container);
  }
  reactRoot.render(h(PriceTrendChartView, props));
}
