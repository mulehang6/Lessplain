import { Lesson } from "./components/Lesson";
import { History } from "./components/History";
import { AnnotationDialog } from "./components/AnnotationDialog";
import { useApp } from "./runtime/store";
import { useCanvasCursor } from "./runtime/cursor";

export function App() {
  useCanvasCursor();
  const view = useApp(state => state.snapshot.views.find(view => view.ref === state.selected));
  const error = useApp(state => state.error);
  return <div className="app">
    {error && <div className="error" role="alert">{error}<button onClick={() => useApp.getState().setError()}>关闭</button></div>}
    <div className="workspace"><main className={view ? "lesson-main" : undefined}>
      {view ? <Lesson key={view.ref} view={view} /> : <History />}
    </main></div>
    <AnnotationDialog />
  </div>;
}
