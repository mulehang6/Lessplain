import { createContext, useContext, useId, useLayoutEffect, useState, type KeyboardEvent } from "react";
import type { BaseComponentProps } from "@json-render/react";
import type { ShadcnProps } from "@json-render/shadcn";

/** 折叠意图独立于内容状态，避免 Tabs/Select 清空当前预留空间。 */
export const FoldContext = createContext<(id: string, value: string | undefined) => void>(() => {});

function useFold(id: string, value: string) {
  const report = useContext(FoldContext);
  useLayoutEffect(() => { report(id, value); }, [id, value, report]);
  useLayoutEffect(() => () => report(id, undefined), [id, report]);
}

function Chevron({ open }: { open: boolean }) {
  return <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
    style={{ flexShrink: 0, transform: open ? "rotate(180deg)" : undefined }}><path d="m6 9 6 6 6-6" /></svg>;
}

function accordionKey(event: KeyboardEvent<HTMLButtonElement>) {
  if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
  const buttons = [...(event.currentTarget.closest(".lesson-accordion")?.querySelectorAll<HTMLButtonElement>(".fold-trigger") ?? [])];
  const current = buttons.indexOf(event.currentTarget);
  if (current < 0) return;
  event.preventDefault(); event.stopPropagation();
  const next = event.key === "Home" ? 0 : event.key === "End" ? buttons.length - 1 :
    (current + (event.key === "ArrowDown" ? 1 : -1) + buttons.length) % buttons.length;
  buttons[next]?.focus();
}

export function FoldCollapsible({ props, children }: BaseComponentProps<ShadcnProps<"Collapsible">>) {
  const fallback = useId();
  const id = (props as { __elementId?: string }).__elementId ?? fallback;
  const [open, setOpen] = useState(props.defaultOpen ?? false);
  useFold(id, open ? "open" : "closed");
  return <section className="lesson-fold" data-state={open ? "open" : "closed"}>
    <button className="fold-trigger" aria-expanded={open} aria-controls={fallback} onClick={() => setOpen(value => !value)}>
      {props.title}<Chevron open={open} />
    </button>
    {open && <div id={fallback} className="fold-content">{children}</div>}
  </section>;
}

export function FoldAccordion({ props }: BaseComponentProps<ShadcnProps<"Accordion">>) {
  const fallback = useId();
  const id = (props as { __elementId?: string }).__elementId ?? fallback;
  const [opened, setOpened] = useState<number[]>([]);
  useFold(id, opened.slice().sort((a, b) => a - b).join(","));
  return <section className="lesson-accordion">{props.items.map((item, index) => {
    const open = opened.includes(index), target = fallback + "-" + index;
    return <section key={index} className="lesson-fold" data-state={open ? "open" : "closed"}>
      <div role="heading" aria-level={3}><button className="fold-trigger" aria-expanded={open} aria-controls={target} onKeyDown={accordionKey} onClick={() => setOpened(previous =>
        previous.includes(index) ? previous.filter(value => value !== index) : props.type === "multiple" ? [...previous, index] : [index])}>
        {item.title}<Chevron open={open} />
      </button></div>{open && <div id={target} className="fold-content">{item.content}</div>}
    </section>;
  })}</section>;
}
