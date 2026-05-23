import { state } from './store.js';

export function navigate(route) {
  state.route = route;
  window.dispatchEvent(new CustomEvent('routechange'));
}

export function bindNav(root) {
  root.querySelectorAll('[data-route]').forEach((button) => {
    button.addEventListener('click', () => navigate(button.dataset.route));
  });
}

