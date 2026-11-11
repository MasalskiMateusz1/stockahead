/*
 * Compact-screen navigation menu. Loaded in <head> without defer so the "js-nav" class is on
 * <html> before the body paints: app.css then hides the nav behind the menu button on narrow
 * screens. Without JavaScript the class is absent and the nav stays a horizontally scrolling row.
 * The open state lives in the button's aria-expanded; the nav markup itself is never touched.
 */
(function () {
	document.documentElement.classList.add('js-nav');

	document.addEventListener('DOMContentLoaded', function () {
		var button = document.querySelector('[data-nav-toggle]');
		if (!button) {
			return;
		}
		var header = button.closest('header');

		function setOpen(open) {
			button.setAttribute('aria-expanded', open ? 'true' : 'false');
			var text = open ? 'Zamknij menu' : 'Menu';
			button.setAttribute('aria-label', text);
			button.setAttribute('title', text);
		}

		button.hidden = false;
		setOpen(false);

		button.addEventListener('click', function () {
			setOpen(button.getAttribute('aria-expanded') !== 'true');
		});

		document.addEventListener('keydown', function (event) {
			if (event.key === 'Escape' && button.getAttribute('aria-expanded') === 'true') {
				setOpen(false);
				button.focus();
			}
		});

		document.addEventListener('click', function (event) {
			if (button.getAttribute('aria-expanded') === 'true' && !header.contains(event.target)) {
				setOpen(false);
			}
		});
	});
})();
