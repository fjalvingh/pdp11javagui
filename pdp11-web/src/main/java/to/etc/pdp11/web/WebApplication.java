package to.etc.pdp11.web;

import to.etc.domui.dom.header.HeaderContributor;
import to.etc.domui.dom.html.UrlPage;
import to.etc.domui.server.ConfigParameters;
import to.etc.domui.server.DomApplication;

/**
 * The web application: minicomputer tools that need no machine at the other end.
 *
 * <p>Everything it shows is computed in pdp11-common, which the desktop application shares. What
 * is in this module is pages and nothing else; an algorithm that turns up here belongs there,
 * with a test, for the same reason the desktop's windows keep theirs out of Swing.</p>
 */
public final class WebApplication extends DomApplication {
	@Override
	protected void initialize(ConfigParameters pp) throws Exception {
		addHeaderContributor(HeaderContributor.loadStylesheet("css/pdp11.css"), 1000);
	}

	@Override
	public Class<? extends UrlPage> getRootPage() {
		return IndexPage.class;
	}
}
