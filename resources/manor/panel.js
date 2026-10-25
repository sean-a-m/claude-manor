// The panel is an operator view. Human forms use only the current seat's tools.
let timer = null, refreshVersion = 0;

function updateStatus(markup) {
  const status = document.getElementById('status');
  const template = document.createElement('template');
  template.innerHTML = markup;
  const oldTurn = status.querySelector('#human-turn');
  const newTurn = template.content.querySelector('#human-turn');
  if (oldTurn && newTurn && oldTurn.dataset.requestId === newTurn.dataset.requestId) {
    // Keep the live form connected: selection, drafts, focus and caret survive SSE.
    for (const node of Array.from(status.children)) if (node !== oldTurn) node.remove();
    let before = true;
    for (const node of Array.from(template.content.children)) {
      if (node === newTurn) before = false;
      else if (before) status.insertBefore(node, oldTurn);
      else status.append(node);
    }
  } else {
    status.replaceChildren(template.content);
  }
}

function refresh() {
  clearTimeout(timer);
  const version = ++refreshVersion;
  timer = setTimeout(async () => {
    try {
      const response = await fetch('/partial/status');
      if (!response.ok) throw new Error(await response.text());
      const markup = await response.text();
      if (version === refreshVersion) {
        updateStatus(markup);
        document.getElementById('panel-client-error').textContent = '';
      }
    } catch (error) {
      document.getElementById('panel-client-error').textContent = 'Refresh failed: ' + error.message;
    }
  }, 150);
}

function send(form) {
  fetch(form.getAttribute('action'), {method: 'POST', body: new URLSearchParams(new FormData(form))})
    .then(refresh);
  return false;
}

function humanMode(form) {
  for (const group of form.querySelectorAll('[data-tool]')) {
    const active = group.dataset.tool === form.elements.action.value;
    const raw = group.querySelector('[data-raw-toggle]').checked;
    group.hidden = !active;
    group.disabled = !active;
    group.querySelector('[data-fields]').disabled = raw;
    group.querySelector('[data-raw]').disabled = !raw;
  }
  form.querySelector('.human-error').textContent = '';
}

function humanArgs(form) {
  const group = Array.from(form.querySelectorAll('[data-tool]'))
    .find(node => node.dataset.tool === form.elements.action.value);
  if (group.querySelector('[data-raw-toggle]').checked) {
    const args = JSON.parse(group.querySelector('[data-raw]').value || '{}');
    if (!args || Array.isArray(args) || typeof args !== 'object') throw new Error('Arguments must be a JSON object.');
    return args;
  }
  const args = Object.create(null);
  for (const input of group.querySelectorAll('[data-arg]')) {
    const value = input.value;
    if (value === '' && input.dataset.required !== 'true') continue;
    try {
      switch (input.dataset.kind) {
        case 'choice': case 'boolean': case 'json':
          args[input.dataset.arg] = JSON.parse(value); break;
        case 'number':
          if (value === '' || !Number.isFinite(Number(value))) throw new Error('Enter a number.');
          args[input.dataset.arg] = Number(value); break;
        case 'lines':
          args[input.dataset.arg] = value.split(/\r?\n/).map(line => line.trim()).filter(Boolean); break;
        default:
          args[input.dataset.arg] = value;
      }
    } catch (error) {
      throw new Error(input.dataset.arg + ': ' + error.message);
    }
  }
  return args;
}

function sendHuman(form) {
  const errorBox = form.querySelector('.human-error');
  errorBox.textContent = '';
  if (!form.reportValidity()) return false;
  let args;
  try { args = humanArgs(form); }
  catch (error) { errorBox.textContent = error.message; return false; }
  const button = form.querySelector('button[type=submit]');
  if (button.disabled) return false;
  button.disabled = true;
  const body = new URLSearchParams(new FormData(form));
  body.set('args', JSON.stringify(args));
  fetch(form.getAttribute('action'), {method: 'POST', body})
    .then(async response => {
      if (!response.ok) throw new Error(await response.text());
      // A new engine prompt replaces this form. Keep this submit disabled until then.
      refresh();
    })
    .catch(error => { errorBox.textContent = error.message; button.disabled = false; });
  return false;
}

const es = new EventSource('/stream' + location.search);
es.addEventListener('ev', message => {
  document.getElementById('feed').insertAdjacentHTML('afterbegin', message.data);
  refresh();
});
es.addEventListener('status', refresh);
