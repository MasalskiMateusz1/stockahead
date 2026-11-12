/*
 * List filters apply themselves. Changing a checkbox or a select inside a list page's GET filter
 * form (.form-inline[method="get"]) submits the form, so "pokaż nieaktywne" or the technician
 * picker reloads the table at once. Text fields still wait for Enter or the submit button.
 * The "js-filters" class lets app.css hide submit buttons that only served the auto-applied
 * controls; without JavaScript the class is absent and every button stays visible.
 */
(function () {
	document.documentElement.classList.add('js-filters');

	document.addEventListener('change', function (event) {
		var control = event.target;
		if (!control.matches('input[type="checkbox"], select')) {
			return;
		}
		var form = control.form;
		if (!form || !form.matches('.form-inline[method="get"]')) {
			return;
		}
		if (form.requestSubmit) {
			form.requestSubmit();
		} else {
			form.submit();
		}
	});
})();
