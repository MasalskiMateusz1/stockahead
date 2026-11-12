/*
 * Number steppers. Every <input type="number"> gets a "−" button before it and a "+" button after
 * it, so a quantity can be changed without the keyboard (the native spinners are tiny on desktop
 * and missing on phones). The buttons use stepDown()/stepUp(), so the field's own min, max and step
 * apply, and they fire "input" and "change" like typing does. Typing still works as before.
 * Without JavaScript the plain number field stays; the server markup is never changed.
 */
(function () {
	function makeButton(label, text, onClick) {
		var button = document.createElement('button');
		button.type = 'button';
		button.className = 'stepper-btn';
		button.textContent = text;
		button.setAttribute('aria-label', label);
		button.tabIndex = -1; // the field itself stays the keyboard target; arrow keys already step it
		button.addEventListener('click', onClick);
		return button;
	}

	function enhance(input) {
		if (input.closest('.stepper') || input.type !== 'number') {
			return;
		}
		var wrapper = document.createElement('span');
		wrapper.className = 'stepper';
		input.parentNode.insertBefore(wrapper, input);

		function step(up) {
			if (input.disabled || input.readOnly) {
				return;
			}
			var before = input.value;
			try {
				if (up) {
					input.stepUp();
				} else {
					input.stepDown();
				}
			} catch (e) {
				return; // a value the browser can't step from (e.g. not a number yet)
			}
			if (input.value !== before) {
				input.dispatchEvent(new Event('input', { bubbles: true }));
				input.dispatchEvent(new Event('change', { bubbles: true }));
			}
		}

		wrapper.appendChild(makeButton('Zmniejsz', '−', function () { step(false); }));
		wrapper.appendChild(input);
		wrapper.appendChild(makeButton('Zwiększ', '+', function () { step(true); }));
	}

	document.addEventListener('DOMContentLoaded', function () {
		document.querySelectorAll('input[type="number"]').forEach(enhance);
	});
})();
