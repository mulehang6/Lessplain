import { createRoot } from "react-dom/client";
import { App } from "./App";
import { bindBridge, ready } from "./runtime/bridge";
import { useApp } from "./runtime/store";
import "@xyflow/react/dist/style.css";
import "./style.css";

bindBridge(message => {
  if (message.snapshot && message.sequence !== undefined) {
    if (message.sequence < useApp.getState().sequence) return;
    useApp.setState({ sequence: message.sequence });
  }
  if (message.snapshot) useApp.getState().receive(message.snapshot);
  if (message.select) useApp.getState().select(message.select);
  if (message.showHistory && window.__lessplainBoot?.host !== "editor") useApp.getState().select();
  if (message.error) useApp.getState().setError(message.error);
  if (message.theme) Object.entries(message.theme).forEach(([name, value]) => document.documentElement.style.setProperty(name, value));
});
createRoot(document.getElementById("root")!).render(<App />);
ready();
