import { useCallback, useEffect, useRef, useState } from "react";
import { Button } from "./ui/button";
import { Input } from "./ui/input";
import { Plus, RefreshCw, Trash2 } from "./icons";
import type { WhisperAshNote } from "../utils/whisperashSettingsSync";

type Draft = { id?: string; updatedAt?: number; title: string; body: string };
const blank: Draft = { title: "", body: "" };

export default function WhisperAshNotes() {
  const bridge = window.whisperash;
  const [notes, setNotes] = useState<WhisperAshNote[]>([]);
  const [folders, setFolders] = useState<Array<{ id: string; name: string }>>([]);
  const [folder, setFolder] = useState("");
  const [query, setQuery] = useState("");
  const [draft, setDraft] = useState<Draft | null>(null);
  const [saved, setSaved] = useState<Draft | null>(null);
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const loadVersion = useRef(0);
  const account = useRef<string | null>(null);

  const refresh = useCallback(async () => {
    const version = ++loadVersion.current;
    try {
      const result = await bridge?.notesList();
      if (version !== loadVersion.current) return;
      if (result?.ok) {
        setNotes(result.result.notes);
        setFolders(result.result.folders);
      } else if (result) setMessage(result.error);
    } catch (error) {
      if (version === loadVersion.current) setMessage(String(error));
    }
  }, [bridge]);

  useEffect(() => {
    if (!bridge) return;
    void refresh();
    const onAccount = (email: string) => {
      const previous = account.current;
      account.current = email;
      if (previous !== null && previous !== email) {
        ++loadVersion.current;
        setNotes([]);
        setFolders([]);
        setFolder("");
        setDraft(null);
        setSaved(null);
        setMessage("");
        void refresh();
      }
    };
    let current = true;
    void bridge.getState().then((s) => { if (current && account.current === null) onAccount(s.email || ""); });
    const offState = bridge.onState((s) => onAccount(s.email || ""));
    const offNotes = bridge.onNotesChanged(() => void refresh());
    return () => { current = false; ++loadVersion.current; offState(); offNotes(); };
  }, [bridge, refresh]);

  const changed = draft && (draft.title !== saved?.title || draft.body !== saved?.body);
  const selected = notes.find((n) => n.id === draft?.id);
  useEffect(() => {
    if (selected && !changed && selected.updatedAt !== draft?.updatedAt) {
      setDraft(selected);
      setSaved(selected);
    }
  }, [selected, changed, draft?.updatedAt]);

  if (!bridge) return null;
  const canEdit = !!draft && (!draft.id || (!!selected && !selected.locked));
  const open = (next: Draft) => {
    if (changed && !window.confirm("Discard unsaved edits?")) return;
    setDraft(next);
    setSaved(next);
    setMessage("");
  };
  const matches = notes.filter((n) => (!folder || n.folderId === folder) &&
    `${n.title}\n${n.body}`.toLowerCase().includes(query.trim().toLowerCase()));

  async function save() {
    if (!draft || !canEdit || !bridge) return;
    setBusy(true);
    setMessage("");
    try {
      const result = await bridge.notesSave(draft);
      if (!result.ok) { setMessage(result.error); return; }
      const next = { ...draft, title: draft.title.trim(), ...result.result };
      setDraft(next);
      setSaved(next);
      await refresh();
    } catch (error) {
      setMessage(String(error));
    } finally { setBusy(false); }
  }

  async function remove() {
    if (!selected || !bridge || !window.confirm("Delete this note from your synced devices?")) return;
    setBusy(true);
    try {
      const result = await bridge.notesDelete({ id: selected.id, updatedAt: selected.updatedAt });
      if (!result.ok) { setMessage(result.error); return; }
      setDraft(null);
      setSaved(null);
      await refresh();
    } catch (error) {
      setMessage(String(error));
    } finally { setBusy(false); }
  }

  async function sync() {
    if (!bridge) return;
    setBusy(true);
    setMessage("");
    try {
      const result = await bridge.syncNow();
      const error = result.error || result.state.lastError;
      if (error) setMessage(error);
      else if (!result.state.signedIn) setMessage("Sign in under Settings to sync notes.");
      await refresh();
    } catch (error) {
      setMessage(String(error));
    } finally { setBusy(false); }
  }

  return (
    <div className="h-full overflow-y-auto px-6 py-5 space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <Button onClick={() => open(blank)} disabled={busy}><Plus size={16} /> New note</Button>
        <Button variant="outline" onClick={() => void sync()} disabled={busy}><RefreshCw size={15} /> Sync now</Button>
      </div>
      {message && <p role="status" className="text-sm text-muted-foreground">{message}</p>}
      <div className="grid gap-4 md:grid-cols-[minmax(210px,1fr)_2fr]">
        <section aria-label="Notes list" className="space-y-2">
          <Input aria-label="Search notes" placeholder="Search notes" value={query} onChange={(e) => setQuery(e.target.value)} />
          {folders.length > 0 && (
            <select aria-label="Filter by folder" className="w-full rounded-md border bg-background px-3 py-2 text-sm"
              value={folder} onChange={(e) => setFolder(e.target.value)}>
              <option value="">All notes</option>
              {folders.map((f) => <option value={f.id} key={f.id}>{f.name}</option>)}
            </select>
          )}
          {matches.length === 0 && <p className="text-sm text-muted-foreground">{notes.length ? "No matching notes." : "No notes yet."}</p>}
          {matches.map((n) => (
            <button className={`block w-full rounded-lg border px-3 py-2 text-left hover:bg-muted/40 ${n.id === draft?.id ? "border-primary/50 bg-muted/60" : "border-transparent"}`}
              type="button" key={n.id} onClick={() => open(n)} disabled={busy}>
              <span className="block truncate text-sm font-medium">{n.title || "Untitled"}</span>
              <span className="block truncate text-xs text-muted-foreground">{n.locked ? "Locked" : n.body}</span>
            </button>
          ))}
        </section>
        {draft ? (
          <section aria-label="Note editor" className="min-w-0 space-y-3 rounded-xl border border-border bg-card p-4">
            {canEdit ? (
              <>
                <Input aria-label="Note title" placeholder="Title" maxLength={200} className="font-semibold"
                  value={draft.title} disabled={busy} onChange={(e) => setDraft({ ...draft, title: e.target.value })} />
                <textarea aria-label="Note text" placeholder="Write a note" rows={12} disabled={busy}
                  className="block w-full resize-y rounded-md border bg-background p-3 text-sm leading-relaxed"
                  value={draft.body} onChange={(e) => setDraft({ ...draft, body: e.target.value })} />
                <div className="flex flex-wrap items-center gap-2">
                  <Button disabled={busy || !changed || (!draft.title.trim() && !draft.body.trim())}
                    onClick={() => void save()}>Save note</Button>
                  {selected && <Button variant="outline" disabled={busy} onClick={() => void remove()}>
                    <Trash2 size={15} /> Delete
                  </Button>}
                </div>
              </>
            ) : (
              <p className="text-sm text-muted-foreground">
                {selected?.locked ? "Unlock this note on your Android phone to edit it here." : "This note was removed on another device."}
              </p>
            )}
          </section>
        ) : (
          <section
            aria-label="Note preview"
            className="hidden min-h-64 items-center justify-center self-start rounded-xl border border-border/60 bg-card/50 p-6 text-center text-sm text-muted-foreground md:flex"
          >
            {notes.length ? "Select a note to view it." : "Create a note to get started."}
          </section>
        )}
      </div>
    </div>
  );
}
