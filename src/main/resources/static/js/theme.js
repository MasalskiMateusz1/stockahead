/*
 * Theme selection. Loaded in <head> without defer so data-theme is set on <html>
 * before the body paints. Stored choice: localStorage "stockahead-theme" = "light" | "dark".
 * Nothing stored (first visit) means the app follows the OS via `color-scheme: light dark`
 * + light-dark() tokens in app.css. The toggle only switches light <-> dark; the first click
 * stores an explicit choice, after which the OS preference is no longer followed.
 */
(function () {
	var STORAGE_KEY = 'stockahead-theme';
	var LABELS = { light: 'Jasny', dark: 'Ciemny' };
	var media = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;

	function readStored() {
		try {
			var value = window.localStorage.getItem(STORAGE_KEY);
			return value === 'light' || value === 'dark' ? value : null;
		} catch (e) {
			return null;
		}
	}

	function writeStored(theme) {
		try {
			window.localStorage.setItem(STORAGE_KEY, theme);
		} catch (e) {
			// Storage unavailable: the choice lasts for this page only.
		}
	}

	// The theme actually shown: the stored choice, or the OS preference when none is stored.
	function effectiveTheme() {
		if (stored) {
			return stored;
		}
		return media && media.matches ? 'dark' : 'light';
	}

	function applyStored() {
		if (stored) {
			document.documentElement.setAttribute('data-theme', stored);
		} else {
			document.documentElement.removeAttribute('data-theme');
		}
	}

	// The button shows only an icon (selected by data-mode in app.css) so its
	// width never changes; the theme name lives in aria-label and title.
	function renderToggle(button) {
		var theme = effectiveTheme();
		var text = 'Motyw: ' + LABELS[theme];
		button.setAttribute('data-mode', theme);
		button.setAttribute('aria-label', text + ' (kliknij, aby zmienić)');
		button.setAttribute('title', text);
	}

	var stored = readStored();
	applyStored();

	document.addEventListener('DOMContentLoaded', function () {
		var toggles = document.querySelectorAll('[data-theme-toggle]');

		function renderAll() {
			Array.prototype.forEach.call(toggles, renderToggle);
		}

		Array.prototype.forEach.call(toggles, function (button) {
			button.hidden = false;
			button.addEventListener('click', function () {
				stored = effectiveTheme() === 'dark' ? 'light' : 'dark';
				applyStored();
				writeStored(stored);
				renderAll();
			});
		});
		renderAll();

		// While no choice is stored, keep the icon in step with an OS theme change.
		if (media && media.addEventListener) {
			media.addEventListener('change', function () {
				if (!stored) {
					renderAll();
				}
			});
		}
	});
})();
