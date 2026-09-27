package to.etc.pdp11.web;

import to.etc.domui.component.misc.ALink;
import to.etc.domui.component2.navigation.BreadCrumb2;
import to.etc.domui.dom.html.Div;
import to.etc.domui.dom.html.Img;
import to.etc.domui.dom.html.Span;

/**
 * The bar across the top of every page: the logo, which goes home, the breadcrumb of the pages
 * that led here, and the light/dark theme switch.
 *
 * <p>Modelled on the DomUI demo's {@code SourceBreadCrumb}, without its link to the page's source
 * and its full-reload button. {@link WebApplication} puts one at the top of every page.</p>
 */
final class TopBar extends Div {
	@Override
	public void createContent() throws Exception {
		setCssClass("pdp-topbar");

		ALink logo = new ALink(IndexPage.class);
		logo.setCssClass("pdp-topbar-logo");
		logo.add(new Img("img/pdp11gui-40.png"));
		logo.add(new Span("pdp-topbar-name", "PDP-11 tools"));
		add(logo);

		Div crumb = new Div("pdp-topbar-crumb");
		add(crumb);
		crumb.add(BreadCrumb2.createPageCrumb("Home", true));

		Div right = new Div("pdp-topbar-r");
		add(right);
		right.add(new ThemeVariantSwitch());
	}
}
