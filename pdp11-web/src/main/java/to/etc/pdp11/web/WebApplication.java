package to.etc.pdp11.web;

import org.eclipse.jdt.annotation.NonNull;
import to.etc.domui.component.layout.ErrorMessageDiv;
import to.etc.domui.dom.header.FaviconContributor;
import to.etc.domui.dom.header.HeaderContributor;
import to.etc.domui.dom.html.NodeBase;
import to.etc.domui.dom.html.NodeContainer;
import to.etc.domui.dom.html.UrlPage;
import to.etc.domui.server.ConfigParameters;
import to.etc.domui.server.DomApplication;
import to.etc.domui.util.INewPageInstantiated;

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
		//-- Keep the light/dark choice across sessions, as the DomUI demo does.
		setThemeVariantCookieName("pdp11tools-theme-variant");

		addHeaderContributor(HeaderContributor.loadStylesheet("css/pdp11.css"), 1000);
		addHeaderContributor(new FaviconContributor("img/pdp11gui-32.png"), 10);

		//-- Every page gets the top bar, so no page has to remember to add it.
		addNewPageInstantiatedListener(new INewPageInstantiated() {
			@Override
			public void newPageBuilt(@NonNull UrlPage body) throws Exception {
				body.add(0, new TopBar());
			}

			@Override
			public void newPageCreated(@NonNull UrlPage body) throws Exception {
			}
		});
	}

	@Override public String getDefaultPageTitle(UrlPage body) {
		return "etc.to tools";
	}

	/** Errors go under the top bar rather than above it. */
	@Override
	public void addDefaultErrorComponent(NodeContainer page) {
		NodeContainer panel = new ErrorMessageDiv(page, true);
		for(int i = 0; i < page.getChildCount(); i++) {
			NodeBase child = page.getChild(i);
			if(child instanceof TopBar) {
				page.add(i + 1, panel);
				return;
			}
		}
		page.add(0, panel);
	}

	@Override
	public Class<? extends UrlPage> getRootPage() {
		return IndexPage.class;
	}
}
