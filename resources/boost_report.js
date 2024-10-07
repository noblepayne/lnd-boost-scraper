function downloadTextArea(textAreaId, fileName) {
  const textArea = document.getElementById(textAreaId);
  const blob = new Blob([textArea.value], { type: "text/plain" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = fileName || "download.txt";
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

function downloadMarkdown() {
  downloadTextArea("markdown", "boosts.md");
  const button = document.getElementById("downloadMarkdown");
  setTimeout(() => {
    button.textContent = "Downloading!";
    setTimeout(() => {
      button.textContent = "Download Markdown";
    }, 1500);
  }, 100);
}

function legacyCopy(sourceElem) {
  sourceElem.style.display = "block";
  sourceElem.focus();
  sourceElem.select();
  document.execCommand("copy");
  sourceElem.style.display = "none";
}

function copyMarkdown() {
  md_el = document.getElementById("markdown");
  md = md_el.textContent;
  try {
    navigator.clipboard.writeText(md).catch((_) => {
      legacyCopy(md_el);
    });
  } catch (error) {
    legacyCopy(md_el);
  }
  const button = document.getElementById("copyMarkdown");
  setTimeout(() => {
    button.textContent = "Copied!";
    setTimeout(() => {
      button.textContent = "Copy Markdown";
    }, 1500);
  }, 100);
}
