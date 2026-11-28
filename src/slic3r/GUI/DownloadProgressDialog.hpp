#ifndef slic3r_DownloadProgressDialog_hpp_
#define slic3r_DownloadProgressDialog_hpp_

#include <string>
#include <unordered_map>
#include <functional>

#include "GUI_Utils.hpp"
#include <wx/dialog.h>
#include <wx/font.h>
#include <wx/bitmap.h>
#include <wx/msgdlg.h>
#include <wx/richmsgdlg.h>
#include <wx/textctrl.h>
#include <wx/statline.h>
#include <wx/simplebook.h>
#include "Widgets/Button.hpp"
#include "BBLStatusBar.hpp"
#include "BBLStatusBarSend.hpp"
#include "Jobs/Worker.hpp"
#include "Jobs/UpgradeNetworkJob.hpp"

class wxBoxSizer;
class wxCheckBox;
class wxStaticBitmap;

#define MSG_DIALOG_BUTTON_SIZE wxSize(FromDIP(58), FromDIP(24))
#define MSG_DIALOG_MIDDLE_BUTTON_SIZE wxSize(FromDIP(76), FromDIP(24))
#define MSG_DIALOG_LONG_BUTTON_SIZE wxSize(FromDIP(90), FromDIP(24))


namespace Slic3r {
namespace GUI {

class DownloadProgressDialog : public DPIDialog
{
protected:

    void on_close(wxCloseEvent& event);


public:
    DownloadProgressDialog(wxString title, std::function<bool(ButtonAction, int)> user_action_callback, int download_id);
    ~DownloadProgressDialog();

    bool Show(bool show) override;
    void on_dpi_changed(const wxRect& suggested_rect) override;
    void download_progress(int progress);
    void download_paused();
    void download_canceled();
    void download_error(const wxString& error_message, const wxString& description);



    wxSimplebook* m_simplebook_status{nullptr};

	std::shared_ptr<BBLStatusBarSend> m_status_bar;
    wxPanel *                         m_panel_download;
    std::function<bool(ButtonAction, int)> m_user_action_callback;
    int                                            m_download_id;
    bool                                   m_close{false};

};


}
}

#endif
