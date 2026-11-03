/* SPDX-License-Identifier: Apache-2.0 */
import "./bridge";
import { startTextReader } from "./text-reader";

let background = false;
void window.paraRead.ready.then((session: import("./text-reader").TextSession) => {
  background = session.settings.backgroundTranslation === true;
  const vscode = acquireVsCodeApi();
  startTextReader({ session, bridge: window.paraRead, post: message => vscode.postMessage(message) });
}).catch((error: unknown) => {
  // Bootstrap errors must be readable even before the reader controls exist.
  if (!background) {
    const status = document.createElement("p"); status.className = "textStatus"; status.setAttribute("role", "alert");
    status.textContent = String(error); document.body.append(status);
  }
  try { window.paraRead.post("session.error", { message: String(error) }); } catch (reportError) { console.error(reportError); }
});
