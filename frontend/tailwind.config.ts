import type { Config } from "tailwindcss";

/**
 * Tailwind 配置 — 直接消费 docs/design/frontend/visual-system.md §9 的 token。
 * 任何 token 调整:先改 design.md,再 sync 本文件,保证 design.md 与工程一致。
 */
export default {
  content: ["./index.html", "./src/**/*.{vue,ts}"],
  theme: {
    extend: {
      colors: {
        primary:   { 50:"#EEF5F0", 100:"#D4E8DA", 500:"#3D8B5F", 600:"#2F7048", 700:"#245539" },
        secondary: { 50:"#FBF1E1", 500:"#E0A458", 700:"#A87530" },
        accent:    { 500:"#C75450", 600:"#A33E3B" },
        bg:        "#FAF7F2",
        surface:   "#FFFFFF",
        border:    { DEFAULT:"#E8E4DD", strong:"#C9C4BB" },
        text:      { primary:"#2C2A26", secondary:"#6B6862", disabled:"#A8A49C", "on-primary":"#FFFFFF" },
        success:   { 500:"#3D8B5F" },
        warning:   { 500:"#E0A458" },
        error:     { 500:"#C75450" },
        info:      { 500:"#4A6FA5" },
      },
      fontFamily: {
        sans: ["Inter", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "PingFang SC", "Microsoft YaHei", "sans-serif"],
        mono: ["JetBrains Mono", "Fira Code", "SF Mono", "Consolas", "monospace"],
        num:  ["Poppins", "Inter", "sans-serif"],
      },
      fontSize: {
        xs:["0.75rem",{lineHeight:"1.25"}], sm:["0.875rem",{lineHeight:"1.5"}],
        base:["1rem",{lineHeight:"1.5"}], lg:["1.125rem",{lineHeight:"1.5"}],
        xl:["1.25rem",{lineHeight:"1.25"}], "2xl":["1.5rem",{lineHeight:"1.25"}],
        "3xl":["1.875rem",{lineHeight:"1.25"}], "4xl":["2.25rem",{lineHeight:"1.25"}],
      },
      spacing: {
        0:"0", 1:"0.25rem", 2:"0.5rem", 3:"0.75rem", 4:"1rem", 5:"1.25rem",
        6:"1.5rem", 8:"2rem", 10:"2.5rem", 12:"3rem", 16:"4rem",
      },
      borderRadius: {
        none:"0", sm:"0.25rem", DEFAULT:"0.375rem", md:"0.375rem",
        lg:"0.5rem", xl:"0.75rem", "2xl":"0.75rem", full:"9999px",
      },
      boxShadow: {
        xs:"0 1px 2px rgba(44, 42, 38, 0.04)",
        sm:"0 1px 3px rgba(44, 42, 38, 0.06), 0 1px 2px rgba(44, 42, 38, 0.04)",
        DEFAULT:"0 1px 3px rgba(44, 42, 38, 0.06), 0 1px 2px rgba(44, 42, 38, 0.04)",
        md:"0 4px 8px rgba(44, 42, 38, 0.08), 0 2px 4px rgba(44, 42, 38, 0.04)",
        lg:"0 10px 20px rgba(44, 42, 38, 0.10), 0 4px 8px rgba(44, 42, 38, 0.06)",
        none:"none",
      },
      transitionDuration: { 150:"150ms", 250:"250ms", 400:"400ms" },
      maxWidth: { content:"72rem", form:"28rem" },
    },
  },
  plugins: [],
} satisfies Config;