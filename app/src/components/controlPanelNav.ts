import type React from "react";
import { useTranslation } from "react-i18next";
import { Home, NotebookPen, BookOpen, Users, Mic } from "./icons";

export type ControlPanelView =
  "home" | "insights" | "chat" | "personal-notes" | "dictionary" | "upload" | "integrations" | "meetings" | "whisperash-notes" | "diaries";

export interface ControlPanelNavItem {
  id: ControlPanelView;
  label: string;
  icon: React.ComponentType<{ size?: number; className?: string }>;
}

/**
 * Single source of truth for main-window navigation. The sidebar renders these
 * rows and the top bar shows the active item's label as the page title, so the
 * two can never disagree.
 */
export function useControlPanelNavItems(): ControlPanelNavItem[] {
  const { t } = useTranslation();
  return [
    { id: "home", label: t("sidebar.home"), icon: Home },
    { id: "meetings", label: t("sidebar.meetings"), icon: Users },
    { id: "whisperash-notes", label: "Notes", icon: NotebookPen },
    { id: "diaries", label: "Diaries", icon: Mic },
    { id: "dictionary", label: t("sidebar.dictionary"), icon: BookOpen },
  ];
}
