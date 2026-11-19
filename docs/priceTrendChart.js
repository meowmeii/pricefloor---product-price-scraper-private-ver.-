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

const MONOCHROME_SERIES = [
  { stroke: "#ffffff", dash: undefined },
  { stroke: "#a1a1aa", dash: "6 3" },
  { stroke: "#e4e4e7", dash: "2 3" },
  { stroke: "#71717a", dash: "10 3 2 3" },
  { stroke: "#d4d4d8", dash: "4 4" },
  { stroke: "#52525b", dash: undefined }
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
    rowMeta.matchedItem
      ? h("div", { className: "trend-tooltip-kind", style: { color: "#d4d4d8" } }, `Item: ${rowMeta.matchedItem}`)
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
            style: { backgroundColor: entry.color || "#ffffff" }
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
          `Platform variance: ${formatMoney(spread, currency)}`
        )
      : null
  );
}

function PriceTrendChartView({
  query,
  matchedProductTitle,
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
      { className: "empty-state", style: { padding: "20px 16px" } },
      h(
        "p",
        { style: { margin: 0 } },
        `No verified matching price points recorded yet for "${query}".`
      )
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
        h("h2", { className: "trend-title" }, "Price History & Platform Variance"),
        h(
          "p",
          { className: "trend-subtitle" },
          matchedProductTitle
            ? `Tracking "${matchedProductTitle}" across ${platforms.length} platform${platforms.length === 1 ? "" : "s"} (${currency})`
            : `Comparing "${query}" across ${platforms.length} platform${platforms.length === 1 ? "" : "s"} (${currency}, ${observationCount} point${observationCount === 1 ? "" : "s"})`
        )
      ),
      h(
        "button",
        {
          type: "button",
          className: "btn btn-secondary btn-xs",
          onClick: onClearHistory
        },
        "Clear History"
      )
    ),
    h(
      "div",
      { className: "trend-stats-row" },
      minPrice != null
        ? h("span", { className: "badge badge--solid" }, `Floor: ${formatMoney(minPrice, currency)}`)
        : null,
      maxPrice != null
        ? h("span", { className: "badge badge--bright" }, `Peak: ${formatMoney(maxPrice, currency)}`)
        : null,
      variance != null
        ? h("span", { className: "badge" }, `Variance: ${formatMoney(variance, currency)}`)
        : null
    ),
    h(
      "div",
      { className: "trend-chart-wrap" },
      h(
        ResponsiveContainer,
        { width: "100%", height: 280 },
        h(
          LineChart,
          {
            data: series,
            margin: { top: 10, right: 18, left: 6, bottom: 8 }
          },
          h("CartesianGrid", {
            strokeDasharray: "3 3",
            stroke: "rgba(255, 255, 255, 0.09)"
          }),
          h("XAxis", {
            dataKey: "label",
            stroke: "#71717a",
            tick: { fill: "#a1a1aa", fontSize: 11 }
          }),
          h("YAxis", {
            stroke: "#71717a",
            tick: { fill: "#a1a1aa", fontSize: 11 },
            tickFormatter: (val) => formatMoney(val, currency),
            width: 80,
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
            wrapperStyle: { fontSize: "12px", paddingTop: "8px", color: "#a1a1aa" }
          }),
          platforms.map((platformName, idx) => {
            const style = MONOCHROME_SERIES[idx % MONOCHROME_SERIES.length];
            return h(Line, {
              key: platformName,
              type: "monotone",
              dataKey: platformName,
              name: platformName,
              stroke: style.stroke,
              strokeDasharray: style.dash,
              strokeWidth: 2.2,
              dot: { r: 4, strokeWidth: 2, fill: "#101012" },
              activeDot: { r: 6, fill: "#ffffff" },
              connectNulls: true,
              isAnimationActive: false
            });
          })
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
