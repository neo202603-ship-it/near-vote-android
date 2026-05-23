import { bindNav } from './core/router.js';
import { ensureKeys, loadPersistentState, state } from './core/store.js';
import { shell } from './core/view.js';
import { bindCompose, composeScreen } from './screens/compose.js';
import { bindDiscover, discoverScreen } from './screens/discover.js';
import { bindHome, homeScreen } from './screens/home.js';
import { bindLedger, ledgerScreen } from './screens/ledger.js';
import { bindMyPage, myPageScreen } from './screens/mypage.js';
import { bindResult, resultScreen } from './screens/result.js';
import { bindVote, voteScreen } from './screens/vote.js';
import { bindWait, waitScreen } from './screens/wait.js';

const app = document.querySelector('#app');

const screens = {
  home: [homeScreen, bindHome],
  compose: [composeScreen, bindCompose],
  ongoing: [discoverScreen, bindDiscover],
  discover: [discoverScreen, bindDiscover],
  vote: [voteScreen, bindVote],
  wait: [waitScreen, bindWait],
  result: [resultScreen, bindResult],
  history: [ledgerScreen, bindLedger],
  ledger: [ledgerScreen, bindLedger],
  mypage: [myPageScreen, bindMyPage]
};

function render() {
  const [screen, bind] = screens[state.route] || screens.home;
  const activeToastId = state.toast?.id;
  app.innerHTML = shell(screen(), state.route, state.toast);
  bindNav(app);
  bind(render);

  if (activeToastId) {
    setTimeout(() => {
      if (state.toast?.id === activeToastId) {
        state.toast = null;
        render();
      }
    }, 2200);
  }
}

window.addEventListener('routechange', render);

loadPersistentState();
ensureKeys().then(render);
