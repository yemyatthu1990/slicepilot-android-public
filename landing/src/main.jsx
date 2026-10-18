import React from "react"
import ReactDOM from "react-dom/client"
import App from "./App.jsx"
import "./index.css"

// simple screenshot/debug helper: ?goto=#features
const goto = new URLSearchParams(window.location.search).get("goto")
if (goto)
  setTimeout(() => {
    document.documentElement.style.scrollBehavior = "auto"
    document.querySelector(goto)?.scrollIntoView()
  }, 2500)

ReactDOM.createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)
