import { createRoot } from "react-dom/client";
import { Gallery } from "./gallery/gallery";
import "./host.css";
createRoot(document.getElementById("root")!).render(<Gallery theme={new URLSearchParams(location.search).get("theme") === "dark" ? "dark" : "light"} />);
