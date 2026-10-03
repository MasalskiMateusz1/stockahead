/*
 * Theme selection. Loaded in <head> without defer so data-theme is set on <html>
 * before the body paints. Stored choice: localStorage "stockahead-theme" = "light" | "dark";
 * absent means Auto (follow the OS via prefers-color-scheme in app.css).
 */
(function () {
	var STORAGE_KEY = 'stockahead-theme';
	var ORDER = ['auto', 'light', 'dark'];
	var LABELS = { auto: 'Auto', light: 'Jasny', dark: 'Ciemny' };

	function readTheme() {
		try {
			var value = window.localStorage.getItem(STORAGE_KEY);
			return value === 'light' || value === 'dark' ? value : 'auto';
		} catch (e) {
			return 'auto';
		}
	}

	function writeTheme(theme) {
		try {
			if (theme === 'auto') {
				window.localStorage.removeItem(STORAGE_KEY);
			} else {
				window.localStorage.setItem(STORAGE_KEY, theme);
			}
		} catch (e) {
			// Storage unavailable: the choice lasts for this page only.
		}
	}

	function applyTheme(theme) {
		if (theme === 'auto') {
			document.documentElement.removeAttribute('data-theme');
		} else {
			document.documentElement.setAttribute('data-theme', theme);
		}
	}

	// The button shows only an icon (selected by data-mode in app.css) so its
	// width never changes; the theme name lives in aria-label and title.
	function renderToggle(button, theme) {
		var text = 'Motyw: ' + LABELS[theme];
		button.setAttribute('data-mode', theme);
		button.setAttribute('aria-label', text + ' (kliknij, aby zmienić)');
		button.setAttribute('title', text);
	}

	var current = readTheme();
	applyTheme(current);

	document.addEventListener('DOMContentLoaded', function () {
		var toggles = document.querySelectorAll('[data-theme-toggle]');
		Array.prototype.forEach.call(toggles, function (button) {
			renderToggle(button, current);
			button.hidden = false;
			button.addEventListener('click', function () {
				current = ORDER[(ORDER.indexOf(current) + 1) % ORDER.length];
				applyTheme(current);
				writeTheme(current);
				Array.prototype.forEach.call(toggles, function (other) {
					renderToggle(other, current);
				});
			});
		});
	});
})();
