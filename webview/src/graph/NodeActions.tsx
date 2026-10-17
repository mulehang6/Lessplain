import { createContext, useCallback, useContext, useEffect, useRef, type ReactNode } from "react";
import { createStore, useStore, type StoreApi } from "zustand";

type Actions = { owner?: string };
const Context = createContext<StoreApi<Actions> | null>(null);

/** 操作栏状态属于当前画布；切换 owner 只更新前后两个节点，不进入图布局数据。 */
export function NodeActionsProvider({ children }: { children: ReactNode }) {
  const store = useRef<StoreApi<Actions> | null>(null);
  store.current ??= createStore<Actions>(() => ({}));
  useEffect(() => {
    const outside = (event: FocusEvent) => {
      if (!(event.target instanceof Element) || !event.target.closest(".lesson-node")) store.current!.setState({ owner: undefined });
    };
    document.addEventListener("focusin", outside);
    return () => document.removeEventListener("focusin", outside);
  }, []);
  return <Context.Provider value={store.current}>{children}</Context.Provider>;
}

export function useNodeActions(id: string) {
  const store = useContext(Context);
  if (!store) throw new Error("节点操作栏缺少画布宿主");
  const visible = useStore(store, state => state.owner === id);
  const show = useCallback(() => { if (store.getState().owner !== id) store.setState({ owner: id }); }, [id, store]);
  const hide = useCallback(() => { if (store.getState().owner === id) store.setState({ owner: undefined }); }, [id, store]);
  return { visible, show, hide };
}
