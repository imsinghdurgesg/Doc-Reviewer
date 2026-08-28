import { useState } from "react";
import { Box, Stack, Typography } from "@mui/material";
import ChatBubbleOutlineIcon from "@mui/icons-material/ChatBubbleOutlineOutlined";
import StorageIcon from "@mui/icons-material/Storage";
import DescriptionOutlinedIcon from "@mui/icons-material/DescriptionOutlined";
import GroupIcon from "@mui/icons-material/Group";
import CloudOutlinedIcon from "@mui/icons-material/CloudOutlined";
import RuleOutlinedIcon from "@mui/icons-material/RuleOutlined";
import { overlineLabel, palette } from "@/app/theme";
import { useCurrentUser } from "@/auth/AuthGate";
import { AiConfigPanel } from "@/features/admin/AiConfigPanel";
import { CloudSyncPanel } from "@/features/admin/CloudSyncPanel";
import { ConditionGovernancePanel } from "@/features/admin/ConditionGovernancePanel";
import { PromptsPanel } from "@/features/admin/PromptsPanel";
import { UsersPanel } from "@/features/admin/UsersPanel";
import { StandardsAdminPage } from "@/features/standards/StandardsAdminPage";

/**
 * Administration. Architecture §8.1, §10, §41.
 *
 * <p>Concerns that are genuinely separate but always configured together: which model runs the review,
 * which standards it measures against, which of their conditions may exclude a control, and where the
 * standards come from. Splitting them across separate routes would mean an administrator setting up a
 * deployment navigates away repeatedly and has to remember what they were doing.
 *
 * <p>Tab state is local rather than in the URL. These are configuration surfaces, not things a
 * reviewer links to or cites — unlike a comparison, which is addressable precisely because it must
 * be quotable weeks later (§19.2).
 */

type TabKey = "ai" | "prompts" | "library" | "conditions" | "cloud" | "users";

const TABS: ReadonlyArray<{
  key: TabKey;
  label: string;
  detail: string;
  icon: React.ReactElement;
  /** Hidden from STANDARDS_ADMIN. Its endpoints refuse them anyway; this avoids a tab that 403s. */
  adminOnly?: boolean;
}> = [
  {
    key: "ai",
    label: "AI & matching",
    detail: "Provider, models, and comparison instructions",
    icon: <ChatBubbleOutlineIcon fontSize="small" />,
  },
  {
    key: "prompts",
    label: "Prompts",
    detail: "What the models are told, and the version recorded on each result",
    icon: <DescriptionOutlinedIcon fontSize="small" />,
  },
  {
    key: "library",
    label: "Standards library",
    detail: "Versions, lifecycle, and extracted requirements",
    icon: <StorageIcon fontSize="small" />,
  },
  {
    key: "conditions",
    label: "Applicability",
    detail: "Which conditions may exclude a control, and who decided",
    icon: <RuleOutlinedIcon fontSize="small" />,
  },
  {
    key: "users",
    label: "Users",
    detail: "Who can sign in, and what they can do",
    icon: <GroupIcon fontSize="small" />,
    // ADMIN only. Curating standards and deciding who may read the organization's reviews are
    // different authorities; STANDARDS_ADMIN holds the first, not the second.
    adminOnly: true,
  },
  {
    key: "cloud",
    label: "Cloud sync",
    detail: "Google Cloud Storage connection",
    icon: <CloudOutlinedIcon fontSize="small" />,
  },
];

export function AdminPage() {
  const [active, setActive] = useState<TabKey>("ai");

  // Users is ADMIN-only. Its endpoints refuse a STANDARDS_ADMIN regardless, so this is not the
  // boundary — it avoids offering a tab that answers 403 to every request, which reads as broken
  // rather than as not-for-you.
  const { user } = useCurrentUser();
  const isAdmin = (user?.roles ?? []).includes("ADMIN");
  const visibleTabs = TABS.filter((tab) => !tab.adminOnly || isAdmin);

  return (
    <Box sx={{ maxWidth: 1180, mx: "auto", p: 4 }}>
      <Typography sx={overlineLabel}>Administration</Typography>
      <Typography sx={{ fontSize: 32, fontWeight: 700, letterSpacing: "-0.9px", mt: 0.5 }}>
        AI &amp; knowledge base
      </Typography>
      <Typography sx={{ fontSize: 14, color: palette.muted, mt: 1, mb: 4 }}>
        Connect a model provider, then curate and index the standards library.
      </Typography>

      {/* Card-style tabs rather than MUI's underline bar: each carries a line of explanation, and a
          one-word tab label does not distinguish "RAG library" from "Cloud sync" for someone
          configuring this for the first time. */}
      <Stack direction="row" spacing={2} sx={{ mb: 3, flexWrap: "wrap", rowGap: 2 }}>
        {visibleTabs.map((tab) => {
          const selected = tab.key === active;
          return (
            <Box
              key={tab.key}
              component="button"
              type="button"
              onClick={() => setActive(tab.key)}
              aria-pressed={selected}
              sx={{
                flex: "1 1 280px",
                textAlign: "left",
                cursor: "pointer",
                display: "flex",
                gap: 1.5,
                alignItems: "center",
                p: 2,
                border: 1,
                borderColor: selected ? palette.forest : "divider",
                // A bottom rule on the selected card, matching the reference implementation.
                borderBottomWidth: selected ? 3 : 1,
                borderRadius: 1,
                bgcolor: selected ? "#fff" : palette.chrome,
                font: "inherit",
                color: "inherit",
                "&:hover": { bgcolor: "#fff" },
              }}
            >
              <Box
                sx={{
                  display: "grid",
                  placeItems: "center",
                  width: 34,
                  height: 34,
                  borderRadius: 1,
                  bgcolor: selected ? palette.mint : "#e8ebe7",
                  color: palette.forest,
                  flexShrink: 0,
                }}
              >
                {tab.icon}
              </Box>
              <Box sx={{ minWidth: 0 }}>
                <Typography sx={{ fontSize: 14, fontWeight: 700 }}>{tab.label}</Typography>
                <Typography sx={{ fontSize: 12, color: palette.muted }}>{tab.detail}</Typography>
              </Box>
            </Box>
          );
        })}
      </Stack>

      {active === "ai" && <AiConfigPanel />}
      {active === "library" && <StandardsAdminPage embedded />}
      {active === "prompts" && <PromptsPanel />}
      {active === "conditions" && <ConditionGovernancePanel />}
      {active === "users" && <UsersPanel />}
      {active === "cloud" && <CloudSyncPanel />}
    </Box>
  );
}
