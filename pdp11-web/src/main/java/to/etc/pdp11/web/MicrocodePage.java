package to.etc.pdp11.web;

import to.etc.domui.component.buttons.DefaultButton;
import to.etc.domui.component.buttons.LinkButton;
import to.etc.domui.component.input.ComboFixed;
import to.etc.domui.component.input.TextStr;
import to.etc.domui.component.input.ValueLabelPair;
import to.etc.domui.dom.html.Div;
import to.etc.domui.dom.html.HTag;
import to.etc.domui.dom.html.Label;
import to.etc.domui.dom.html.Span;
import to.etc.domui.dom.html.TBody;
import to.etc.domui.dom.html.TD;
import to.etc.domui.dom.html.TH;
import to.etc.domui.dom.html.THead;
import to.etc.domui.dom.html.TR;
import to.etc.domui.dom.html.Table;
import to.etc.domui.dom.html.UrlPage;
import to.etc.domui.state.UIGoto;
import to.etc.pdp11.common.microcode.MicroInstruction;
import to.etc.pdp11.common.microcode.MicrocodeBrowser;
import to.etc.pdp11.common.microcode.MicrocodeBrowser.SearchBy;
import to.etc.pdp11.common.microcode.MicrocodeSource;
import to.etc.pdp11.common.microcode.MicrowordRow;

import java.util.ArrayList;
import java.util.List;

/**
 * A processor's microcode, one microword at a time: the web counterpart of the desktop's
 * Microcode window, and a view over the same {@link MicrocodeBrowser}.
 *
 * <p>Two things differ from the desktop, both because a page is not a window. The document is
 * one of the packaged ones - there is no uploading another scan yet. And the microwords the
 * current one falls through from are links, since a click is how a page gets about.</p>
 *
 * <p>{@code ?source=PDP1105_F&upc=123} opens on a given document and µPC, so a microword can be
 * linked to from anywhere.</p>
 */
public final class MicrocodePage extends UrlPage {
	/** Which document: a {@link MicrocodeSource} constant name. */
	public static final String PARAM_SOURCE = "source";

	/** Which microword to open on, as an octal address. */
	public static final String PARAM_UPC = "upc";

	private final MicrocodeBrowser m_browser = new MicrocodeBrowser(new Slf4jLogger(MicrocodePage.class));

	private final HTag m_title = new HTag(1);

	private final Div m_searchByHolder = new Div("pdp-inline");

	private ComboFixed<SearchBy> m_searchBy;

	private final TextStr m_search = new TextStr();

	private final DefaultButton m_back = new DefaultButton("Back", b -> {
		m_browser.back();
		showCurrent();
	});

	private final DefaultButton m_next = new DefaultButton("Next instruction", b -> show(m_browser.next()));

	/** Why the last search or step went nowhere; empty when it went somewhere. */
	private final Div m_message = new Div("pdp-message");

	private final Div m_view = new Div("pdp-microword");

	private final Div m_status = new Div("pdp-status");

	@Override
	public void createContent() throws Exception {
		addCssClass("pdp-page");
		MicrocodeSource source = sourceParameter();
		m_browser.choose(source);

		add(new LinkButton("PDP-11 tools", b -> UIGoto.moveSub(IndexPage.class)));
		add(m_title);

		Div bar = new Div("pdp-controls");
		add(bar);
		bar.add(new Label("Microcode:"));
		List<ValueLabelPair<MicrocodeSource>> sources = new ArrayList<>();
		for(MicrocodeSource s : MicrocodeSource.values())
			sources.add(new ValueLabelPair<>(s, s.getLabel()));
		ComboFixed<MicrocodeSource> sourceCombo = new ComboFixed<>(sources);
		sourceCombo.setMandatory(true);
		sourceCombo.setValue(source);
		sourceCombo.setTitle("The two PDP-11/05 entries are the two M7261 board revisions: read the part"
			+ " numbers off the two control store PROMs to tell which board is in the machine.");
		sourceCombo.setOnValueChanged(c -> {
			MicrocodeSource chosen = sourceCombo.getValue();
			if(chosen == null)
				return;
			m_browser.choose(chosen);
			buildSearchBy();
			show(null);
		});
		bar.add(sourceCombo);

		bar.add(new Label("Search by:"));
		bar.add(m_searchByHolder);
		buildSearchBy();

		bar.add(new Label("µInstruction:"));
		m_search.addCssClass("pdp-mono");
		m_search.setOnValueChanged(c -> search());
		bar.add(m_search);
		bar.add(new DefaultButton("Go", b -> search()));
		bar.add(m_back);
		m_next.setTitle("Follow this microword's next-address field, which is where it goes when nothing branches");
		bar.add(m_next);

		add(m_message);
		add(m_view);
		add(m_status);

		String upc = getPage().getPageParameters().getString(PARAM_UPC, null);
		show(upc == null ? null : m_browser.searchFor(upc));
	}

	/** The document the URL asks for; the default for none, and for one this version does not have. */
	private MicrocodeSource sourceParameter() {
		String name = getPage().getPageParameters().getString(PARAM_SOURCE, null);
		if(name != null) {
			for(MicrocodeSource s : MicrocodeSource.values()) {
				if(s.name().equals(name))
					return s;
			}
		}
		return MicrocodeSource.DEFAULT;
	}

	/** The search orders this document supports: the KD11-B transcription has no line numbers. */
	private void buildSearchBy() {
		List<ValueLabelPair<SearchBy>> items = new ArrayList<>();
		for(SearchBy by : SearchBy.availableFor(m_browser.getSource()))
			items.add(new ValueLabelPair<>(by, by.getLabel()));
		m_searchBy = new ComboFixed<>(items);
		m_searchBy.setMandatory(true);
		m_searchBy.setValue(m_browser.getSearchBy());
		m_searchBy.setOnValueChanged(c -> {
			m_browser.setSearchBy(m_searchBy.getValue());
			showCurrent();
		});
		m_searchByHolder.removeAllChildren();
		m_searchByHolder.add(m_searchBy);
	}

	private void search() {
		show(m_browser.searchFor(m_search.getValue()));
	}

	/** Show where a move went, or - leaving what is on screen alone - why it could not go. */
	private void show(String whyNot) {
		m_message.setText(whyNot);
		if(whyNot == null)
			showCurrent();
	}

	private void showCurrent() {
		MicroInstruction mi = m_browser.getCurrent();
		MicrocodeSource source = m_browser.getSource();
		m_title.setText("Microcode - " + source.getLabel());
		setPageTitle("Microcode - " + source.getLabel());
		m_search.setValue(mi == null ? null : m_browser.searchTextOf(mi));
		m_back.setDisabled(!m_browser.canGoBack());
		m_next.setDisabled(!m_browser.canGoNext());

		m_status.setText(m_browser.statusText());
		m_status.removeCssClass("pdp-trouble");
		if(m_browser.isStatusTroubled())
			m_status.addCssClass("pdp-trouble");

		m_view.removeAllChildren();
		if(mi == null)
			return;
		Table table = new Table("pdp-rows");
		m_view.add(table);
		THead head = new THead();
		table.add(head);
		TR hr = new TR();
		head.add(hr);
		for(String h : new String[]{"Field", "Bits", "Info"}) {
			TH th = new TH();
			th.setText(h);
			hr.add(th);
		}
		TBody body = new TBody();
		table.add(body);
		MicrocodeSource other = source.getOther();
		for(MicrowordRow r : m_browser.rows()) {
			TR tr = body.addRow();
			if(r.differs())
				tr.addCssClass("pdp-differs");
			else if(r.highlight())
				tr.addCssClass("pdp-highlight");
			tr.addCell().setText(r.label());
			tr.addCell("pdp-mono").setText(r.bits());
			TD info = tr.addCell("pdp-info");
			if(r.differs() && other != null)
				tr.setTitle("This field is different in " + other.getLabel());
			if(r.next()) {
				//-- Where it goes next is a link, as a double-click on the row is on the desktop.
				info.add(new LinkButton(r.info(), b -> show(m_browser.next())));
			} else if(MicrowordRow.PREDECESSORS.equals(r.label()) && !m_browser.predecessors().isEmpty()) {
				//-- The one thing a page can do that the window's table cannot: each microword that
				//-- falls through to this one is somewhere to go.
				for(MicroInstruction from : m_browser.predecessors()) {
					if(info.getChildCount() > 0)
						info.add(new Span(", "));
					int address = from.getAddress();
					info.add(new LinkButton(from.getSymbolicTag() + " (" + from.getAddressOctal() + ")",
						b -> {
							m_browser.goTo(address);
							show(null);
						}));
				}
			} else {
				info.setText(r.info());
			}
		}
	}
}
